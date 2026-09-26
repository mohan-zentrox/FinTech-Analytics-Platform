package com.zentrox.ledger.fraud;

import com.zentrox.ledger.entity.AlertSeverity;
import com.zentrox.ledger.entity.FraudRule;
import com.zentrox.ledger.entity.Transaction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FRD S5.4 "Anomaly and Fraud Detection" - the rule set itself.
 *
 * Deliberately a pure function of (transactions, thresholds): no repository, no
 * Spring context, no clock beyond the caller-supplied asOf date. Every rule is
 * therefore directly unit-testable against hand-built lists - the same design
 * the reconciliation matcher already uses.
 *
 * Rules implemented:
 *   1. {@link FraudRule#DUPLICATE_PAYMENT} - same account, same absolute amount
 *      and same normalised description within duplicateWindowDays.
 *   2. {@link FraudRule#AMOUNT_OUTLIER} - absolute z-score of the amount within
 *      its (account, category) peer group above outlierZThreshold.
 *   3. {@link FraudRule#VELOCITY_SPIKE} - a day whose transaction count sits
 *      velocityZThreshold standard deviations above that account's daily mean.
 *   4. {@link FraudRule#ROUND_AMOUNT} - a large, exactly-round amount.
 *
 * Scores are clamped so they always fit fraud_alert.score NUMERIC(10,4).
 */
@Component
@RequiredArgsConstructor
public class FraudRuleEngine {

    private static final BigDecimal MAX_SCORE = new BigDecimal("999999.9999");
    private static final int SCORE_SCALE = 4;

    /** Fewer active days than this gives no usable velocity baseline. */
    private static final int MIN_ACTIVE_DAYS_FOR_VELOCITY = 3;

    private final FraudProperties properties;

    /**
     * Evaluates every rule over the supplied transactions, which the caller has
     * already restricted to the window of interest. The caller decides how to
     * persist the findings.
     */
    public List<FraudFinding> evaluate(List<Transaction> transactions, LocalDate asOf) {
        List<FraudFinding> findings = new ArrayList<>();
        if (transactions == null || transactions.isEmpty()) {
            return findings;
        }
        findings.addAll(detectDuplicatePayments(transactions));
        findings.addAll(detectAmountOutliers(transactions));
        findings.addAll(detectVelocitySpikes(transactions));
        findings.addAll(detectRoundAmounts(transactions));
        return findings;
    }

    // ---------------------------------------------------------------- rule 1

    List<FraudFinding> detectDuplicatePayments(List<Transaction> transactions) {
        List<FraudFinding> findings = new ArrayList<>();
        Map<String, List<Transaction>> groups = new LinkedHashMap<>();

        for (Transaction tx : transactions) {
            String key = tx.getAccount() + "|"
                    + tx.getAmount().abs().stripTrailingZeros().toPlainString() + "|"
                    + normalizeDescription(tx.getDescription());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(tx);
        }

        for (List<Transaction> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            List<Transaction> ordered = new ArrayList<>(group);
            ordered.sort(Comparator.comparing(Transaction::getPostedDate)
                    .thenComparing(tx -> String.valueOf(tx.getExternalId())));

            // Each transaction is compared against the one immediately before it,
            // so only the later of a pair is flagged - the original payment never
            // becomes an alert against itself.
            for (int i = 1; i < ordered.size(); i++) {
                Transaction previous = ordered.get(i - 1);
                Transaction current = ordered.get(i);
                long gap = ChronoUnit.DAYS.between(previous.getPostedDate(), current.getPostedDate());
                if (gap > properties.getDuplicateWindowDays()) {
                    continue;
                }
                findings.add(new FraudFinding(
                        current.getId(),
                        current.getAccount(),
                        FraudRule.DUPLICATE_PAYMENT,
                        gap == 0 ? AlertSeverity.HIGH : AlertSeverity.MEDIUM,
                        score(BigDecimal.ONE),
                        String.format(Locale.ROOT,
                                "Possible duplicate payment: same account, amount %s and description as the entry posted %s (%d day(s) earlier, externalId=%s)",
                                current.getAmount().toPlainString(),
                                previous.getPostedDate(),
                                gap,
                                previous.getExternalId())));
            }
        }
        return findings;
    }

    /** Lowercased, punctuation-free, whitespace-collapsed description; null collapses to empty. */
    private String normalizeDescription(String description) {
        if (description == null) {
            return "";
        }
        return description.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // ---------------------------------------------------------------- rule 2

    List<FraudFinding> detectAmountOutliers(List<Transaction> transactions) {
        List<FraudFinding> findings = new ArrayList<>();
        Map<String, List<Transaction>> groups = new LinkedHashMap<>();

        for (Transaction tx : transactions) {
            String category = tx.getCategory() == null || tx.getCategory().isBlank()
                    ? "uncategorized"
                    : tx.getCategory().toLowerCase(Locale.ROOT);
            groups.computeIfAbsent(tx.getAccount() + "|" + category, k -> new ArrayList<>()).add(tx);
        }

        double threshold = properties.getOutlierZThreshold().doubleValue();

        for (Map.Entry<String, List<Transaction>> entry : groups.entrySet()) {
            List<Transaction> group = entry.getValue();
            if (group.size() < properties.getOutlierMinSampleSize()) {
                continue;
            }
            double[] amounts = group.stream()
                    .mapToDouble(tx -> tx.getAmount().abs().doubleValue())
                    .toArray();
            double mean = mean(amounts);
            double stdDev = sampleStdDev(amounts, mean);
            if (stdDev <= 0.0) {
                // Every amount in the peer group is identical; no outlier is definable.
                continue;
            }

            for (Transaction tx : group) {
                double z = Math.abs(tx.getAmount().abs().doubleValue() - mean) / stdDev;
                if (z < threshold) {
                    continue;
                }
                findings.add(new FraudFinding(
                        tx.getId(),
                        tx.getAccount(),
                        FraudRule.AMOUNT_OUTLIER,
                        severityFor(z, threshold),
                        score(BigDecimal.valueOf(z)),
                        String.format(Locale.ROOT,
                                "Amount %s is %.2f standard deviations from the peer-group (%s) mean of %.2f over %d transactions",
                                tx.getAmount().toPlainString(), z, entry.getKey(), mean, group.size())));
            }
        }
        return findings;
    }

    // ---------------------------------------------------------------- rule 3

    List<FraudFinding> detectVelocitySpikes(List<Transaction> transactions) {
        List<FraudFinding> findings = new ArrayList<>();
        Map<String, Map<LocalDate, List<Transaction>>> byAccountAndDay = new LinkedHashMap<>();

        for (Transaction tx : transactions) {
            byAccountAndDay
                    .computeIfAbsent(tx.getAccount(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(tx.getPostedDate(), k -> new ArrayList<>())
                    .add(tx);
        }

        double threshold = properties.getVelocityZThreshold().doubleValue();

        for (Map.Entry<String, Map<LocalDate, List<Transaction>>> accountEntry : byAccountAndDay.entrySet()) {
            Map<LocalDate, List<Transaction>> days = accountEntry.getValue();
            if (days.size() < MIN_ACTIVE_DAYS_FOR_VELOCITY) {
                continue;
            }
            double[] dailyCounts = days.values().stream().mapToDouble(List::size).toArray();
            double mean = mean(dailyCounts);
            double stdDev = sampleStdDev(dailyCounts, mean);
            if (stdDev <= 0.0) {
                continue;
            }

            for (Map.Entry<LocalDate, List<Transaction>> dayEntry : days.entrySet()) {
                List<Transaction> dayTransactions = dayEntry.getValue();
                int count = dayTransactions.size();
                if (count < properties.getVelocityMinDailyCount()) {
                    continue;
                }
                double z = (count - mean) / stdDev;
                if (z < threshold) {
                    continue;
                }
                // One alert per spiking day, raised against the largest transaction
                // of that day as its representative: flagging all N would bury the
                // analyst in near-identical alerts for a single underlying event.
                Transaction representative = dayTransactions.stream()
                        .max(Comparator.comparing(tx -> tx.getAmount().abs()))
                        .orElse(dayTransactions.get(0));

                findings.add(new FraudFinding(
                        representative.getId(),
                        representative.getAccount(),
                        FraudRule.VELOCITY_SPIKE,
                        z >= threshold + 2 ? AlertSeverity.HIGH : AlertSeverity.MEDIUM,
                        score(BigDecimal.valueOf(z)),
                        String.format(Locale.ROOT,
                                "%d transactions posted on %s against account %s - %.2f standard deviations above its daily mean of %.2f",
                                count, dayEntry.getKey(), accountEntry.getKey(), z, mean)));
            }
        }
        return findings;
    }

    // ---------------------------------------------------------------- rule 4

    List<FraudFinding> detectRoundAmounts(List<Transaction> transactions) {
        List<FraudFinding> findings = new ArrayList<>();
        BigDecimal multiple = properties.getRoundAmountMultiple();
        BigDecimal floor = properties.getRoundAmountFloor();

        if (multiple == null || multiple.signum() <= 0) {
            return findings;
        }

        for (Transaction tx : transactions) {
            BigDecimal magnitude = tx.getAmount().abs();
            if (magnitude.compareTo(floor) < 0) {
                continue;
            }
            if (magnitude.remainder(multiple).signum() != 0) {
                continue;
            }
            BigDecimal ratio = magnitude.divide(floor, SCORE_SCALE, RoundingMode.HALF_UP);
            findings.add(new FraudFinding(
                    tx.getId(),
                    tx.getAccount(),
                    FraudRule.ROUND_AMOUNT,
                    ratio.compareTo(BigDecimal.TEN) >= 0 ? AlertSeverity.MEDIUM : AlertSeverity.LOW,
                    score(ratio),
                    String.format(Locale.ROOT,
                            "Suspiciously round amount %s: an exact multiple of %s at or above the %s review floor",
                            tx.getAmount().toPlainString(), multiple.toPlainString(), floor.toPlainString())));
        }
        return findings;
    }

    // ---------------------------------------------------------------- helpers

    private static AlertSeverity severityFor(double z, double threshold) {
        if (z >= threshold + 2) {
            return AlertSeverity.HIGH;
        }
        if (z >= threshold + 1) {
            return AlertSeverity.MEDIUM;
        }
        return AlertSeverity.LOW;
    }

    private static double mean(double[] values) {
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    /** Sample (n-1) standard deviation; 0 for fewer than two observations. */
    private static double sampleStdDev(double[] values, double mean) {
        if (values.length < 2) {
            return 0.0;
        }
        double sumSquares = 0.0;
        for (double v : values) {
            double d = v - mean;
            sumSquares += d * d;
        }
        return Math.sqrt(sumSquares / (values.length - 1));
    }

    private static BigDecimal score(BigDecimal raw) {
        BigDecimal clamped = raw.min(MAX_SCORE).max(BigDecimal.ZERO);
        return clamped.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    }
}
