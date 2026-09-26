package com.zentrox.ledger.fraud;

import com.zentrox.ledger.dto.fraud.AlertDecisionRequest;
import com.zentrox.ledger.dto.fraud.FraudScanRequest;
import com.zentrox.ledger.dto.fraud.FraudScanResult;
import com.zentrox.ledger.entity.AlertStatus;
import com.zentrox.ledger.entity.FraudAlert;
import com.zentrox.ledger.entity.FraudRule;
import com.zentrox.ledger.entity.Transaction;
import com.zentrox.ledger.entity.TransactionStatus;
import com.zentrox.ledger.exception.ResourceNotFoundException;
import com.zentrox.ledger.repository.FraudAlertRepository;
import com.zentrox.ledger.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FraudDetectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-30T12:00:00Z");

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private FraudAlertRepository fraudAlertRepository;

    private FraudProperties properties;
    private FraudDetectionService service;

    @BeforeEach
    void setUp() {
        properties = new FraudProperties();
        service = new FraudDetectionService(
                transactionRepository,
                fraudAlertRepository,
                new FraudRuleEngine(properties),
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));

        when(fraudAlertRepository.save(any(FraudAlert.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fraudAlertRepository.findByTransactionIdAndRuleId(any(), any())).thenReturn(Optional.empty());
    }

    private Transaction tx(String amount, LocalDate date, String description) {
        return Transaction.builder()
                .id(UUID.randomUUID())
                .source("manual")
                .account("ACC-1")
                .amount(new BigDecimal(amount))
                .currency("USD")
                .postedDate(date)
                .description(description)
                .category("opex")
                .status(TransactionStatus.POSTED)
                .externalId(UUID.randomUUID().toString())
                .build();
    }

    private List<Transaction> duplicatePair() {
        return List.of(
                tx("-2500.00", LocalDate.of(2026, 6, 10), "Acme invoice 42"),
                tx("-2500.00", LocalDate.of(2026, 6, 11), "Acme invoice 42"));
    }

    @Test
    void defaultsTheScanWindowToTheConfiguredLookbackEndingToday() {
        properties.setLookbackDays(30);
        when(transactionRepository.findByPostedDateBetween(any(), any())).thenReturn(List.of());

        FraudScanResult result = service.scan(FraudScanRequest.all());

        assertThat(result.dateTo()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(result.dateFrom()).isEqualTo(LocalDate.of(2026, 5, 31));
        verify(transactionRepository).findByPostedDateBetween(
                LocalDate.of(2026, 5, 31), LocalDate.of(2026, 6, 30));
    }

    @Test
    void scopesTheQueryToOneAccountWhenRequested() {
        when(transactionRepository.findByAccountAndPostedDateBetween(eq("ACC-7"), any(), any()))
                .thenReturn(List.of());

        service.scan(new FraudScanRequest("ACC-7", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)));

        verify(transactionRepository).findByAccountAndPostedDateBetween(
                "ACC-7", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
        verify(transactionRepository, never()).findByPostedDateBetween(any(), any());
    }

    @Test
    void rejectsAnInvertedWindow() {
        assertThatThrownBy(() -> service.scan(new FraudScanRequest(
                null, LocalDate.of(2026, 6, 30), LocalDate.of(2026, 6, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dateFrom must not be after dateTo");
    }

    @Test
    void persistsNewFindingsAsOpenAlertsStampedWithOneScanId() {
        when(transactionRepository.findByPostedDateBetween(any(), any())).thenReturn(duplicatePair());

        FraudScanResult result = service.scan(FraudScanRequest.all());

        assertThat(result.findings()).isPositive();
        assertThat(result.newAlerts()).isEqualTo(result.findings());
        assertThat(result.updatedAlerts()).isZero();
        assertThat(result.findingsByRule()).containsKey(FraudRule.DUPLICATE_PAYMENT);

        ArgumentCaptor<FraudAlert> captor = ArgumentCaptor.forClass(FraudAlert.class);
        verify(fraudAlertRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(alert -> {
            assertThat(alert.getStatus()).isEqualTo(AlertStatus.OPEN);
            assertThat(alert.getScanId()).isEqualTo(result.scanId());
            assertThat(alert.getDetectedAt()).isEqualTo(NOW);
            assertThat(alert.getAccount()).isEqualTo("ACC-1");
        });
    }

    @Test
    void refreshesAnExistingOpenAlertInsteadOfCreatingASecondOne() {
        List<Transaction> transactions = duplicatePair();
        when(transactionRepository.findByPostedDateBetween(any(), any())).thenReturn(transactions);

        FraudAlert existing = FraudAlert.builder()
                .id(UUID.randomUUID())
                .scanId(UUID.randomUUID())
                .transactionId(transactions.get(1).getId())
                .account("ACC-1")
                .ruleId(FraudRule.DUPLICATE_PAYMENT)
                .status(AlertStatus.OPEN)
                .score(new BigDecimal("0.5000"))
                .detectedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        when(fraudAlertRepository.findByTransactionIdAndRuleId(
                transactions.get(1).getId(), FraudRule.DUPLICATE_PAYMENT))
                .thenReturn(Optional.of(existing));

        FraudScanResult result = service.scan(FraudScanRequest.all());

        assertThat(result.updatedAlerts()).isEqualTo(1);
        assertThat(result.newAlerts()).isZero();
        assertThat(existing.getScanId()).isEqualTo(result.scanId());
        assertThat(existing.getDetectedAt()).isEqualTo(NOW);
    }

    @Test
    void neverReopensAnAlertAnAnalystHasAlreadyTriaged() {
        List<Transaction> transactions = duplicatePair();
        when(transactionRepository.findByPostedDateBetween(any(), any())).thenReturn(transactions);

        FraudAlert dismissed = FraudAlert.builder()
                .id(UUID.randomUUID())
                .transactionId(transactions.get(1).getId())
                .account("ACC-1")
                .ruleId(FraudRule.DUPLICATE_PAYMENT)
                .status(AlertStatus.DISMISSED)
                .score(BigDecimal.ONE)
                .resolutionNote("Confirmed with the vendor: two genuine invoices")
                .detectedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        when(fraudAlertRepository.findByTransactionIdAndRuleId(
                transactions.get(1).getId(), FraudRule.DUPLICATE_PAYMENT))
                .thenReturn(Optional.of(dismissed));

        FraudScanResult result = service.scan(FraudScanRequest.all());

        assertThat(result.suppressedAlerts()).isEqualTo(1);
        assertThat(dismissed.getStatus()).isEqualTo(AlertStatus.DISMISSED);
        assertThat(dismissed.getResolutionNote()).isEqualTo("Confirmed with the vendor: two genuine invoices");
        verify(fraudAlertRepository, never()).save(dismissed);
    }

    @Test
    void doesNotTouchTransactionStatusUnlessAutoFlagIsEnabled() {
        when(transactionRepository.findByPostedDateBetween(any(), any())).thenReturn(duplicatePair());
        properties.setAutoFlagTransactions(false);

        service.scan(FraudScanRequest.all());

        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void flagsOffendingTransactionsWhenAutoFlagIsEnabled() {
        List<Transaction> transactions = duplicatePair();
        when(transactionRepository.findByPostedDateBetween(any(), any())).thenReturn(transactions);
        properties.setAutoFlagTransactions(true);

        service.scan(FraudScanRequest.all());

        assertThat(transactions.get(1).getStatus()).isEqualTo(TransactionStatus.FLAGGED);
        // The original of the pair was never a finding, so it keeps its status.
        assertThat(transactions.get(0).getStatus()).isEqualTo(TransactionStatus.POSTED);
        verify(transactionRepository).save(transactions.get(1));
    }

    @Test
    void autoFlagLeavesVoidTransactionsAlone() {
        List<Transaction> transactions = duplicatePair();
        transactions.get(1).setStatus(TransactionStatus.VOID);
        when(transactionRepository.findByPostedDateBetween(any(), any())).thenReturn(transactions);
        properties.setAutoFlagTransactions(true);

        service.scan(FraudScanRequest.all());

        assertThat(transactions.get(1).getStatus()).isEqualTo(TransactionStatus.VOID);
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    // ----------------------------------------------------------------- triage

    @Test
    void recordsWhoResolvedAnAlertAndWhen() {
        FraudAlert alert = FraudAlert.builder()
                .id(UUID.randomUUID())
                .transactionId(UUID.randomUUID())
                .account("ACC-1")
                .ruleId(FraudRule.AMOUNT_OUTLIER)
                .status(AlertStatus.OPEN)
                .score(BigDecimal.ONE)
                .build();
        when(fraudAlertRepository.findById(alert.getId())).thenReturn(Optional.of(alert));

        FraudAlert result = service.decide(alert.getId(),
                new AlertDecisionRequest(AlertStatus.CONFIRMED, "Escalated to finance"), "t2-data1");

        assertThat(result.getStatus()).isEqualTo(AlertStatus.CONFIRMED);
        assertThat(result.getResolvedBy()).isEqualTo("t2-data1");
        assertThat(result.getResolvedAt()).isEqualTo(NOW);
        assertThat(result.getResolutionNote()).isEqualTo("Escalated to finance");
    }

    @Test
    void reopeningAnAlertClearsItsResolutionMetadata() {
        FraudAlert alert = FraudAlert.builder()
                .id(UUID.randomUUID())
                .transactionId(UUID.randomUUID())
                .account("ACC-1")
                .ruleId(FraudRule.AMOUNT_OUTLIER)
                .status(AlertStatus.DISMISSED)
                .score(BigDecimal.ONE)
                .resolvedAt(Instant.parse("2026-02-02T00:00:00Z"))
                .resolvedBy("t2-qa1")
                .build();
        when(fraudAlertRepository.findById(alert.getId())).thenReturn(Optional.of(alert));

        FraudAlert result = service.decide(alert.getId(),
                new AlertDecisionRequest(AlertStatus.OPEN, null), "t2-lead");

        assertThat(result.getStatus()).isEqualTo(AlertStatus.OPEN);
        assertThat(result.getResolvedAt()).isNull();
        assertThat(result.getResolvedBy()).isNull();
    }

    @Test
    void decidingOnAMissingAlertIsANotFound() {
        UUID id = UUID.randomUUID();
        when(fraudAlertRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decide(id, new AlertDecisionRequest(AlertStatus.CONFIRMED, null), "x"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void openAlertCountIsScopedByAccountWhenGivenOne() {
        when(fraudAlertRepository.countByStatus(AlertStatus.OPEN)).thenReturn(9L);
        when(fraudAlertRepository.countByAccountAndStatus("ACC-1", AlertStatus.OPEN)).thenReturn(4L);

        assertThat(service.countOpenAlerts(null)).isEqualTo(9L);
        assertThat(service.countOpenAlerts("  ")).isEqualTo(9L);
        assertThat(service.countOpenAlerts("ACC-1")).isEqualTo(4L);
    }
}
