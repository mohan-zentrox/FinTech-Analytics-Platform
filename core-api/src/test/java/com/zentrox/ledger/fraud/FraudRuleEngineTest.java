package com.zentrox.ledger.fraud;

import com.zentrox.ledger.entity.AlertSeverity;
import com.zentrox.ledger.entity.FraudRule;
import com.zentrox.ledger.entity.Transaction;
import com.zentrox.ledger.entity.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the FRD S5.4 rule set. The engine is a pure function, so these
 * run with no Spring context and no database - hand-built transaction lists and
 * an explicit FraudProperties instance only.
 */
class FraudRuleEngineTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 6, 30);

    private FraudProperties properties;
    private FraudRuleEngine engine;

    @BeforeEach
    void setUp() {
        properties = new FraudProperties();
        engine = new FraudRuleEngine(properties);
    }

    private Transaction tx(String account, String amount, LocalDate date, String description, String category) {
        return Transaction.builder()
                .id(UUID.randomUUID())
                .source("manual")
                .account(account)
                .amount(new BigDecimal(amount))
                .currency("USD")
                .postedDate(date)
                .description(description)
                .category(category)
                .status(TransactionStatus.POSTED)
                .externalId(UUID.randomUUID().toString())
                .build();
    }

    // ------------------------------------------------------------- duplicates

    @Test
    void flagsOnlyTheLaterOfTwoIdenticalPaymentsInsideTheWindow() {
        Transaction original = tx("ACC-1", "-2500.00", LocalDate.of(2026, 6, 1), "Acme Corp invoice 88", "opex");
        Transaction repeat = tx("ACC-1", "-2500.00", LocalDate.of(2026, 6, 3), "ACME CORP - Invoice #88!", "opex");

        List<FraudFinding> findings = engine.detectDuplicatePayments(List.of(original, repeat));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).transactionId()).isEqualTo(repeat.getId());
        assertThat(findings.get(0).rule()).isEqualTo(FraudRule.DUPLICATE_PAYMENT);
        assertThat(findings.get(0).severity()).isEqualTo(AlertSeverity.MEDIUM);
        assertThat(findings.get(0).reason()).contains("duplicate payment");
    }

    @Test
    void treatsSameDayDuplicatesAsHighSeverity() {
        LocalDate day = LocalDate.of(2026, 6, 10);
        Transaction first = tx("ACC-1", "-900.00", day, "Vendor payment", "opex");
        Transaction second = tx("ACC-1", "-900.00", day, "Vendor payment", "opex");

        List<FraudFinding> findings = engine.detectDuplicatePayments(List.of(first, second));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).severity()).isEqualTo(AlertSeverity.HIGH);
    }

    @Test
    void ignoresIdenticalPaymentsOutsideTheDuplicateWindow() {
        properties.setDuplicateWindowDays(7);
        Transaction january = tx("ACC-1", "-2500.00", LocalDate.of(2026, 1, 5), "Monthly rent", "opex");
        Transaction february = tx("ACC-1", "-2500.00", LocalDate.of(2026, 2, 5), "Monthly rent", "opex");

        assertThat(engine.detectDuplicatePayments(List.of(january, february))).isEmpty();
    }

    @Test
    void doesNotTreatSameAmountOnDifferentAccountsAsDuplicate() {
        Transaction a = tx("ACC-1", "-500.00", LocalDate.of(2026, 6, 1), "Subscription", "opex");
        Transaction b = tx("ACC-2", "-500.00", LocalDate.of(2026, 6, 1), "Subscription", "opex");

        assertThat(engine.detectDuplicatePayments(List.of(a, b))).isEmpty();
    }

    // ---------------------------------------------------------------- outlier

    @Test
    void flagsAmountFarFromItsPeerGroupMean() {
        List<Transaction> transactions = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            transactions.add(tx("ACC-1", "100.00", LocalDate.of(2026, 6, 1).plusDays(i), "Normal " + i, "supplies"));
        }
        // Nudge one value so the peer group has a non-zero standard deviation.
        transactions.set(0, tx("ACC-1", "110.00", LocalDate.of(2026, 6, 1), "Normal 0", "supplies"));
        Transaction whopper = tx("ACC-1", "50000.00", LocalDate.of(2026, 6, 20), "Unusual", "supplies");
        transactions.add(whopper);

        List<FraudFinding> findings = engine.detectAmountOutliers(transactions);

        assertThat(findings).extracting(FraudFinding::transactionId).contains(whopper.getId());
        assertThat(findings).allMatch(f -> f.rule() == FraudRule.AMOUNT_OUTLIER);
        assertThat(findings).allSatisfy(f -> assertThat(f.score()).isGreaterThan(BigDecimal.valueOf(3)));
    }

    @Test
    void skipsPeerGroupsSmallerThanTheMinimumSampleSize() {
        properties.setOutlierMinSampleSize(8);
        List<Transaction> small = List.of(
                tx("ACC-1", "10.00", LocalDate.of(2026, 6, 1), "a", "travel"),
                tx("ACC-1", "12.00", LocalDate.of(2026, 6, 2), "b", "travel"),
                tx("ACC-1", "99999.00", LocalDate.of(2026, 6, 3), "c", "travel"));

        assertThat(engine.detectAmountOutliers(small)).isEmpty();
    }

    @Test
    void doesNotDivideByZeroWhenEveryPeerAmountIsIdentical() {
        List<Transaction> identical = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            identical.add(tx("ACC-1", "250.00", LocalDate.of(2026, 6, 1).plusDays(i), "Same " + i, "fees"));
        }

        assertThat(engine.detectAmountOutliers(identical)).isEmpty();
    }

    @Test
    void separatesPeerGroupsByCategory() {
        // 10 small "supplies" rows plus 10 large "capex" rows. Neither group is
        // anomalous internally, so pooling them would produce false positives.
        List<Transaction> transactions = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            transactions.add(tx("ACC-1", String.valueOf(100 + i), LocalDate.of(2026, 6, 1).plusDays(i), "s" + i, "supplies"));
            transactions.add(tx("ACC-1", String.valueOf(90000 + i), LocalDate.of(2026, 6, 1).plusDays(i), "c" + i, "capex"));
        }

        assertThat(engine.detectAmountOutliers(transactions)).isEmpty();
    }

    // --------------------------------------------------------------- velocity

    @Test
    void raisesOneAlertPerSpikingDayAgainstItsLargestTransaction() {
        List<Transaction> transactions = new ArrayList<>();
        // Baseline: one transaction a day for 20 days.
        for (int i = 0; i < 20; i++) {
            transactions.add(tx("ACC-1", "50.00", LocalDate.of(2026, 6, 1).plusDays(i), "baseline " + i, "opex"));
        }
        // Spike: 15 transactions on a single day, the largest being 5,000.
        LocalDate spikeDay = LocalDate.of(2026, 6, 25);
        Transaction largest = tx("ACC-1", "-5000.00", spikeDay, "biggest of the burst", "opex");
        transactions.add(largest);
        for (int i = 0; i < 14; i++) {
            transactions.add(tx("ACC-1", "20.00", spikeDay, "burst " + i, "opex"));
        }

        List<FraudFinding> findings = engine.detectVelocitySpikes(transactions);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).transactionId()).isEqualTo(largest.getId());
        assertThat(findings.get(0).rule()).isEqualTo(FraudRule.VELOCITY_SPIKE);
        assertThat(findings.get(0).reason()).contains("15 transactions");
    }

    @Test
    void ignoresBusyDaysBelowTheMinimumDailyCount() {
        properties.setVelocityMinDailyCount(5);
        List<Transaction> transactions = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            transactions.add(tx("ACC-1", "50.00", LocalDate.of(2026, 6, 1).plusDays(i), "baseline " + i, "opex"));
        }
        LocalDate busyDay = LocalDate.of(2026, 6, 25);
        for (int i = 0; i < 3; i++) {
            transactions.add(tx("ACC-1", "50.00", busyDay, "mildly busy " + i, "opex"));
        }

        assertThat(engine.detectVelocitySpikes(transactions)).isEmpty();
    }

    @Test
    void ignoresAccountsWithTooFewActiveDaysToEstablishABaseline() {
        LocalDate day = LocalDate.of(2026, 6, 2);
        List<Transaction> transactions = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            transactions.add(tx("ACC-NEW", "10.00", day, "same day " + i, "opex"));
        }

        assertThat(engine.detectVelocitySpikes(transactions)).isEmpty();
    }

    // ----------------------------------------------------------- round amount

    @Test
    void flagsLargeExactlyRoundAmounts() {
        Transaction round = tx("ACC-1", "-50000.00", LocalDate.of(2026, 6, 1), "Consulting", "opex");

        List<FraudFinding> findings = engine.detectRoundAmounts(List.of(round));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).rule()).isEqualTo(FraudRule.ROUND_AMOUNT);
        // 50,000 is 5x the 10,000 review floor: notable, not alarming.
        assertThat(findings.get(0).severity()).isEqualTo(AlertSeverity.LOW);
        assertThat(findings.get(0).score()).isEqualByComparingTo("5.0000");
    }

    @Test
    void escalatesRoundAmountsAtOrAboveTenTimesTheReviewFloor() {
        Transaction veryLarge = tx("ACC-1", "-100000.00", LocalDate.of(2026, 6, 1), "Consulting", "opex");

        List<FraudFinding> findings = engine.detectRoundAmounts(List.of(veryLarge));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).severity()).isEqualTo(AlertSeverity.MEDIUM);
    }

    @Test
    void ignoresRoundAmountsBelowTheReviewFloorAndNonRoundLargeAmounts() {
        Transaction smallRound = tx("ACC-1", "-2000.00", LocalDate.of(2026, 6, 1), "Small", "opex");
        Transaction largeNotRound = tx("ACC-1", "-48321.17", LocalDate.of(2026, 6, 2), "Large", "opex");

        assertThat(engine.detectRoundAmounts(List.of(smallRound, largeNotRound))).isEmpty();
    }

    @Test
    void roundAmountRuleIsDisabledWhenTheMultipleIsZero() {
        properties.setRoundAmountMultiple(BigDecimal.ZERO);
        Transaction round = tx("ACC-1", "-50000.00", LocalDate.of(2026, 6, 1), "Consulting", "opex");

        assertThat(engine.detectRoundAmounts(List.of(round))).isEmpty();
    }

    // ------------------------------------------------------------- evaluate()

    @Test
    void evaluateReturnsNoFindingsForAnEmptyOrNullLedger() {
        assertThat(engine.evaluate(List.of(), AS_OF)).isEmpty();
        assertThat(engine.evaluate(null, AS_OF)).isEmpty();
    }

    @Test
    void evaluateCombinesEveryRule() {
        List<Transaction> transactions = new ArrayList<>();
        // A duplicate pair that is also large and round -> two different rules fire.
        transactions.add(tx("ACC-1", "-20000.00", LocalDate.of(2026, 6, 1), "Bonus payout", "payroll"));
        transactions.add(tx("ACC-1", "-20000.00", LocalDate.of(2026, 6, 2), "Bonus payout", "payroll"));

        List<FraudFinding> findings = engine.evaluate(transactions, AS_OF);

        assertThat(findings).extracting(FraudFinding::rule)
                .contains(FraudRule.DUPLICATE_PAYMENT, FraudRule.ROUND_AMOUNT);
    }

    @Test
    void scoresAlwaysFitTheFraudAlertScoreColumn() {
        // score is NUMERIC(10,4): six integer digits, four decimals.
        List<Transaction> transactions = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            transactions.add(tx("ACC-1", "1.00", LocalDate.of(2026, 6, 1).plusDays(i), "tiny " + i, "opex"));
        }
        transactions.set(0, tx("ACC-1", "2.00", LocalDate.of(2026, 6, 1), "tiny 0", "opex"));
        transactions.add(tx("ACC-1", "99999999999.00", LocalDate.of(2026, 6, 20), "astronomical", "opex"));

        List<FraudFinding> findings = engine.evaluate(transactions, AS_OF);

        assertThat(findings).isNotEmpty();
        assertThat(findings).allSatisfy(f -> {
            assertThat(f.score().scale()).isEqualTo(4);
            assertThat(f.score().precision() - f.score().scale()).isLessThanOrEqualTo(6);
        });
    }
}
