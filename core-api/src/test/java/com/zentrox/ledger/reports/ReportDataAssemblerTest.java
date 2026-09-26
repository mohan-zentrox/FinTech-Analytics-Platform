package com.zentrox.ledger.reports;

import com.zentrox.ledger.entity.AlertSeverity;
import com.zentrox.ledger.entity.AlertStatus;
import com.zentrox.ledger.entity.FraudAlert;
import com.zentrox.ledger.entity.FraudRule;
import com.zentrox.ledger.entity.MatchType;
import com.zentrox.ledger.entity.ReconciliationMatch;
import com.zentrox.ledger.entity.Transaction;
import com.zentrox.ledger.entity.TransactionStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReportDataAssemblerTest {

    private Transaction tx(String account, String amount, LocalDate date) {
        return Transaction.builder()
                .id(UUID.randomUUID())
                .source("manual")
                .account(account)
                .amount(new BigDecimal(amount))
                .currency("USD")
                .postedDate(date)
                .description("desc")
                .status(TransactionStatus.POSTED)
                .externalId(UUID.randomUUID().toString())
                .build();
    }

    // ------------------------------------------------------------- cash flow

    @Test
    void cashFlowSplitsInflowFromOutflowPerMonth() {
        List<Transaction> transactions = List.of(
                tx("ACC-1", "1000.00", LocalDate.of(2026, 1, 10)),
                tx("ACC-1", "-400.00", LocalDate.of(2026, 1, 20)),
                tx("ACC-1", "-250.50", LocalDate.of(2026, 2, 5)));

        ReportData data = ReportDataAssembler.cashFlow(
                "ACC-1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28), transactions);

        assertThat(data.columns()).containsExactly("Month", "Inflow", "Outflow", "Net");
        assertThat(data.rows()).hasSize(2);
        assertThat(data.rows().get(0)).containsExactly("2026-01", "1000.00", "400.00", "600.00");
        assertThat(data.rows().get(1)).containsExactly("2026-02", "0.00", "250.50", "-250.50");
        assertThat(data.summary())
                .containsEntry("Total inflow", "1000.00")
                .containsEntry("Total outflow", "650.50")
                .containsEntry("Net cash movement", "349.50")
                .containsEntry("Transactions", "3");
    }

    @Test
    void cashFlowIncludesMonthsWithNoActivitySoTheSeriesHasNoGaps() {
        ReportData data = ReportDataAssembler.cashFlow(
                "ACC-1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 30),
                List.of(tx("ACC-1", "500.00", LocalDate.of(2026, 3, 15))));

        assertThat(data.rows()).extracting(row -> row.get(0))
                .containsExactly("2026-01", "2026-02", "2026-03", "2026-04");
        assertThat(data.rows().get(0)).containsExactly("2026-01", "0.00", "0.00", "0.00");
    }

    @Test
    void cashFlowAverageMonthlyBurnIsPositiveWhenSpendingExceedsIncome() {
        // Two months, 900 net outflow -> 450/month burn.
        ReportData data = ReportDataAssembler.cashFlow(
                "ACC-1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28),
                List.of(tx("ACC-1", "-600.00", LocalDate.of(2026, 1, 5)),
                        tx("ACC-1", "-300.00", LocalDate.of(2026, 2, 5))));

        assertThat(data.summary()).containsEntry("Average monthly burn", "450.00");
    }

    @Test
    void cashFlowHandlesAnEmptyLedgerWithoutFailing() {
        ReportData data = ReportDataAssembler.cashFlow(
                null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), List.of());

        assertThat(data.rows()).hasSize(1);
        assertThat(data.subtitle()).contains("all accounts");
        assertThat(data.summary()).containsEntry("Total inflow", "0.00");
    }

    @Test
    void cashFlowTreatsZeroAmountAsInflowNotOutflow() {
        ReportData data = ReportDataAssembler.cashFlow(
                "ACC-1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                List.of(tx("ACC-1", "0.00", LocalDate.of(2026, 1, 10))));

        assertThat(data.rows().get(0)).containsExactly("2026-01", "0.00", "0.00", "0.00");
    }

    @Test
    void cashFlowNumericColumnsAreRightAligned() {
        ReportData data = ReportDataAssembler.cashFlow(
                "ACC-1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), List.of());

        assertThat(data.columnAlignments()).containsExactly(
                ReportData.Alignment.LEFT, ReportData.Alignment.RIGHT,
                ReportData.Alignment.RIGHT, ReportData.Alignment.RIGHT);
    }

    // ------------------------------------------- reconciliation exceptions

    @Test
    void reconciliationExceptionsEnrichesEachRowWithItsTransaction() {
        Transaction orphan = tx("ACC-1", "-125.00", LocalDate.of(2026, 3, 3));
        UUID runId = UUID.randomUUID();
        ReconciliationMatch exception = ReconciliationMatch.builder()
                .id(UUID.randomUUID())
                .runId(runId)
                .transactionId(orphan.getId())
                .matchType(MatchType.EXCEPTION)
                .createdAt(Instant.parse("2026-03-04T10:00:00Z"))
                .build();

        Map<UUID, Transaction> byId = Map.of(orphan.getId(), orphan);

        ReportData data = ReportDataAssembler.reconciliationExceptions(
                "ACC-1", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), List.of(exception), byId);

        assertThat(data.rows()).hasSize(1);
        assertThat(data.rows().get(0)).containsExactly(
                runId.toString(), "2026-03-03", "ACC-1", "manual", "desc", "-125.00", "POSTED");
        assertThat(data.summary())
                .containsEntry("Unmatched transactions", "1")
                .containsEntry("Total unmatched value", "125.00")
                .containsEntry("Distinct runs", "1");
    }

    @Test
    void reconciliationExceptionsKeepsRowsWhoseTransactionIsGoneRatherThanDroppingThem() {
        UUID missingTransactionId = UUID.randomUUID();
        ReconciliationMatch exception = ReconciliationMatch.builder()
                .id(UUID.randomUUID())
                .runId(UUID.randomUUID())
                .transactionId(missingTransactionId)
                .matchType(MatchType.EXCEPTION)
                .createdAt(Instant.parse("2026-03-04T10:00:00Z"))
                .build();

        ReportData data = ReportDataAssembler.reconciliationExceptions(
                null, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31),
                List.of(exception), new HashMap<>());

        assertThat(data.rows()).hasSize(1);
        assertThat(data.rows().get(0).get(6)).contains("no longer in ledger");
        assertThat(data.summary()).containsEntry("Unmatched transactions", "1");
    }

    @Test
    void reconciliationExceptionsIgnoresMatchedRows() {
        Transaction a = tx("ACC-1", "10.00", LocalDate.of(2026, 3, 1));
        Transaction b = tx("ACC-1", "10.00", LocalDate.of(2026, 3, 1));
        ReconciliationMatch matched = ReconciliationMatch.builder()
                .id(UUID.randomUUID())
                .runId(UUID.randomUUID())
                .transactionId(a.getId())
                .matchedTransactionId(b.getId())
                .matchType(MatchType.MATCHED)
                .createdAt(Instant.parse("2026-03-02T10:00:00Z"))
                .build();

        ReportData data = ReportDataAssembler.reconciliationExceptions(
                "ACC-1", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31),
                List.of(matched), Map.of(a.getId(), a, b.getId(), b));

        assertThat(data.rows()).isEmpty();
        assertThat(data.summary()).containsEntry("Unmatched transactions", "0");
    }

    // ----------------------------------------------------------- fraud alerts

    @Test
    void fraudAlertsSummarisesCountsByStatus() {
        FraudAlert open = alert(AlertStatus.OPEN, FraudRule.DUPLICATE_PAYMENT, "2026-04-02T00:00:00Z");
        FraudAlert dismissed = alert(AlertStatus.DISMISSED, FraudRule.ROUND_AMOUNT, "2026-04-01T00:00:00Z");

        ReportData data = ReportDataAssembler.fraudAlerts(
                "ACC-1", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), List.of(dismissed, open));

        assertThat(data.rows()).hasSize(2);
        // Newest first.
        assertThat(data.rows().get(0).get(2)).isEqualTo("DUPLICATE_PAYMENT");
        assertThat(data.summary())
                .containsEntry("Total alerts", "2")
                .containsEntry("OPEN alerts", "1")
                .containsEntry("DISMISSED alerts", "1")
                .containsEntry("CONFIRMED alerts", "0");
    }

    private FraudAlert alert(AlertStatus status, FraudRule rule, String detectedAt) {
        return FraudAlert.builder()
                .id(UUID.randomUUID())
                .scanId(UUID.randomUUID())
                .transactionId(UUID.randomUUID())
                .account("ACC-1")
                .ruleId(rule)
                .severity(AlertSeverity.MEDIUM)
                .score(new BigDecimal("3.5000"))
                .reason("because")
                .status(status)
                .detectedAt(Instant.parse(detectedAt))
                .build();
    }

    // --------------------------------------------------------- invariants

    @Test
    void reportDataRejectsAnAlignmentListThatDoesNotMatchItsColumns() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                new ReportData("t", "s", Map.of(),
                        List.of("a", "b"), List.of(ReportData.Alignment.LEFT), List.of()));
    }
}
