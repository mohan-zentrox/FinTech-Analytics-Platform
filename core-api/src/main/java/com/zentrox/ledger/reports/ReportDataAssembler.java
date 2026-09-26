package com.zentrox.ledger.reports;

import com.zentrox.ledger.entity.AlertStatus;
import com.zentrox.ledger.entity.FraudAlert;
import com.zentrox.ledger.entity.MatchType;
import com.zentrox.ledger.entity.ReconciliationMatch;
import com.zentrox.ledger.entity.Transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns ledger rows into {@link ReportData} (FRD S6.1).
 *
 * Pure functions of their inputs - no repositories, no Spring - so every report's
 * content is unit-testable without rendering or parsing a binary document. This
 * is also why the monthly cash-flow aggregation is done here in Java rather than
 * by calling analytics-service: a group-by over one account's rows is cheap, and
 * a report must not fail because a separate (possibly scaled-to-zero) service is
 * unreachable.
 */
public final class ReportDataAssembler {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final int MONEY_SCALE = 2;

    private ReportDataAssembler() {
    }

    // ------------------------------------------------------------- cash flow

    /**
     * Monthly inflow / outflow / net for the period, with every calendar month
     * in range present even when it had no activity - a gap-free table is what
     * makes period-over-period comparison possible.
     */
    public static ReportData cashFlow(String account, LocalDate from, LocalDate to,
                                      List<Transaction> transactions) {
        Map<YearMonth, BigDecimal[]> byMonth = new LinkedHashMap<>();
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
            byMonth.put(m, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        }

        for (Transaction tx : transactions) {
            YearMonth month = YearMonth.from(tx.getPostedDate());
            BigDecimal[] bucket = byMonth.get(month);
            if (bucket == null) {
                // Defensive: a caller that passed transactions outside the window.
                continue;
            }
            if (tx.getAmount().signum() >= 0) {
                bucket[0] = bucket[0].add(tx.getAmount());
            } else {
                bucket[1] = bucket[1].add(tx.getAmount().abs());
            }
        }

        List<List<String>> rows = new ArrayList<>();
        BigDecimal totalInflow = BigDecimal.ZERO;
        BigDecimal totalOutflow = BigDecimal.ZERO;

        for (Map.Entry<YearMonth, BigDecimal[]> entry : byMonth.entrySet()) {
            BigDecimal inflow = entry.getValue()[0];
            BigDecimal outflow = entry.getValue()[1];
            totalInflow = totalInflow.add(inflow);
            totalOutflow = totalOutflow.add(outflow);
            rows.add(List.of(
                    entry.getKey().format(MONTH),
                    money(inflow),
                    money(outflow),
                    money(inflow.subtract(outflow))));
        }

        int months = byMonth.size();
        BigDecimal net = totalInflow.subtract(totalOutflow);
        BigDecimal averageBurn = months == 0 ? BigDecimal.ZERO
                : totalOutflow.subtract(totalInflow)
                        .divide(BigDecimal.valueOf(months), MONEY_SCALE, RoundingMode.HALF_UP);

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Total inflow", money(totalInflow));
        summary.put("Total outflow", money(totalOutflow));
        summary.put("Net cash movement", money(net));
        summary.put("Average monthly burn", money(averageBurn));
        summary.put("Transactions", String.valueOf(transactions.size()));

        return new ReportData(
                "Cash Flow Statement",
                subtitle(account, from, to),
                summary,
                List.of("Month", "Inflow", "Outflow", "Net"),
                List.of(ReportData.Alignment.LEFT, ReportData.Alignment.RIGHT,
                        ReportData.Alignment.RIGHT, ReportData.Alignment.RIGHT),
                rows);
    }

    // ------------------------------------------- reconciliation exceptions

    /**
     * Unmatched transactions from reconciliation runs in the period.
     *
     * @param exceptions EXCEPTION rows from reconciliation_match
     * @param transactionsById lookup for enriching each exception with its
     *                         transaction detail; entries with no matching
     *                         transaction are reported with blank detail rather
     *                         than being silently dropped, so the count in the
     *                         summary always reconciles with the table.
     */
    public static ReportData reconciliationExceptions(String account, LocalDate from, LocalDate to,
                                                      List<ReconciliationMatch> exceptions,
                                                      Map<java.util.UUID, Transaction> transactionsById) {
        List<List<String>> rows = new ArrayList<>();
        BigDecimal totalExposure = BigDecimal.ZERO;

        List<ReconciliationMatch> ordered = new ArrayList<>(exceptions);
        ordered.sort(Comparator.comparing(ReconciliationMatch::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));

        for (ReconciliationMatch match : ordered) {
            if (match.getMatchType() != MatchType.EXCEPTION) {
                continue;
            }
            Transaction tx = transactionsById.get(match.getTransactionId());
            if (tx != null) {
                totalExposure = totalExposure.add(tx.getAmount().abs());
            }
            rows.add(List.of(
                    String.valueOf(match.getRunId()),
                    tx == null ? "" : String.valueOf(tx.getPostedDate()),
                    tx == null ? "" : nullSafe(tx.getAccount()),
                    tx == null ? "" : nullSafe(tx.getSource()),
                    tx == null ? "" : nullSafe(tx.getDescription()),
                    tx == null ? "" : money(tx.getAmount()),
                    tx == null ? "(transaction no longer in ledger)" : String.valueOf(tx.getStatus())));
        }

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Unmatched transactions", String.valueOf(rows.size()));
        summary.put("Total unmatched value", money(totalExposure));
        summary.put("Distinct runs", String.valueOf(
                ordered.stream().map(ReconciliationMatch::getRunId).distinct().count()));

        return new ReportData(
                "Reconciliation Exception Report",
                subtitle(account, from, to),
                summary,
                List.of("Run", "Posted", "Account", "Source", "Description", "Amount", "Status"),
                List.of(ReportData.Alignment.LEFT, ReportData.Alignment.LEFT, ReportData.Alignment.LEFT,
                        ReportData.Alignment.LEFT, ReportData.Alignment.LEFT, ReportData.Alignment.RIGHT,
                        ReportData.Alignment.LEFT),
                rows);
    }

    // ----------------------------------------------------------- fraud alerts

    /** Anomaly alerts raised in the period (FRD S5.4 output as a deliverable document). */
    public static ReportData fraudAlerts(String account, LocalDate from, LocalDate to,
                                         List<FraudAlert> alerts) {
        List<List<String>> rows = new ArrayList<>();
        Map<AlertStatus, Integer> byStatus = new LinkedHashMap<>();

        List<FraudAlert> ordered = new ArrayList<>(alerts);
        ordered.sort(Comparator.comparing(FraudAlert::getDetectedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        for (FraudAlert alert : ordered) {
            byStatus.merge(alert.getStatus(), 1, Integer::sum);
            rows.add(List.of(
                    alert.getDetectedAt() == null ? "" : alert.getDetectedAt().toString(),
                    nullSafe(alert.getAccount()),
                    String.valueOf(alert.getRuleId()),
                    String.valueOf(alert.getSeverity()),
                    alert.getScore() == null ? "" : alert.getScore().stripTrailingZeros().toPlainString(),
                    String.valueOf(alert.getStatus()),
                    nullSafe(alert.getReason())));
        }

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Total alerts", String.valueOf(rows.size()));
        for (AlertStatus status : AlertStatus.values()) {
            summary.put(status.name() + " alerts", String.valueOf(byStatus.getOrDefault(status, 0)));
        }

        return new ReportData(
                "Anomaly Alert Report",
                subtitle(account, from, to),
                summary,
                List.of("Detected", "Account", "Rule", "Severity", "Score", "Status", "Reason"),
                List.of(ReportData.Alignment.LEFT, ReportData.Alignment.LEFT, ReportData.Alignment.LEFT,
                        ReportData.Alignment.LEFT, ReportData.Alignment.RIGHT, ReportData.Alignment.LEFT,
                        ReportData.Alignment.LEFT),
                rows);
    }

    // ---------------------------------------------------------------- helpers

    private static String subtitle(String account, LocalDate from, LocalDate to) {
        return String.format("Account: %s   |   Period: %s to %s",
                account == null || account.isBlank() ? "all accounts" : account, from, to);
    }

    /** Plain signed decimal - no currency symbol, so XlsxReportRenderer can store it as a number. */
    private static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
