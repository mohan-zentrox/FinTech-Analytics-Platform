package com.zentrox.ledger.service;

import com.zentrox.ledger.entity.Transaction;
import com.zentrox.ledger.entity.TransactionStatus;
import com.zentrox.ledger.repository.ReconciliationMatchRepository;
import com.zentrox.ledger.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private ReconciliationMatchRepository matchRepository;

    private ReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        reconciliationService = new ReconciliationService(transactionRepository, matchRepository);
    }

    private Transaction tx(String account, BigDecimal amount, LocalDate date) {
        return Transaction.builder()
                .id(UUID.randomUUID())
                .source("manual")
                .account(account)
                .amount(amount)
                .currency("USD")
                .postedDate(date)
                .status(TransactionStatus.POSTED)
                .externalId(UUID.randomUUID().toString())
                .build();
    }

    @Test
    void matchesTransactionsWithinAmountAndDateTolerance() {
        Transaction a1 = tx("ACC-1", new BigDecimal("100.00"), LocalDate.of(2026, 1, 10));
        Transaction b1 = tx("ACC-1", new BigDecimal("100.02"), LocalDate.of(2026, 1, 11)); // within tolerance

        var outcome = reconciliationService.match(
                List.of(a1), List.of(b1), new BigDecimal("0.05"), 2);

        assertThat(outcome.matchedPairs()).hasSize(1);
        assertThat(outcome.matchedPairs().get(0).a()).isEqualTo(a1);
        assertThat(outcome.matchedPairs().get(0).b()).isEqualTo(b1);
        assertThat(outcome.exceptionsA()).isEmpty();
        assertThat(outcome.exceptionsB()).isEmpty();
    }

    @Test
    void leavesUnmatchedTransactionsAsExceptionsOnBothSides() {
        Transaction a1 = tx("ACC-1", new BigDecimal("100.00"), LocalDate.of(2026, 1, 10));
        Transaction b1 = tx("ACC-1", new BigDecimal("500.00"), LocalDate.of(2026, 1, 10)); // amount too far off

        var outcome = reconciliationService.match(
                List.of(a1), List.of(b1), new BigDecimal("0.05"), 2);

        assertThat(outcome.matchedPairs()).isEmpty();
        assertThat(outcome.exceptionsA()).containsExactly(a1);
        assertThat(outcome.exceptionsB()).containsExactly(b1);
    }

    @Test
    void picksClosestAmountWhenMultipleCandidatesQualify() {
        Transaction a1 = tx("ACC-1", new BigDecimal("100.00"), LocalDate.of(2026, 1, 10));
        Transaction bFar = tx("ACC-1", new BigDecimal("100.04"), LocalDate.of(2026, 1, 10));
        Transaction bClose = tx("ACC-1", new BigDecimal("100.01"), LocalDate.of(2026, 1, 10));

        var outcome = reconciliationService.match(
                List.of(a1), List.of(bFar, bClose), new BigDecimal("0.05"), 2);

        assertThat(outcome.matchedPairs()).hasSize(1);
        assertThat(outcome.matchedPairs().get(0).b()).isEqualTo(bClose);
        assertThat(outcome.exceptionsB()).containsExactly(bFar);
    }

    @Test
    void eachTransactionIsMatchedAtMostOnce() {
        Transaction a1 = tx("ACC-1", new BigDecimal("100.00"), LocalDate.of(2026, 1, 10));
        Transaction a2 = tx("ACC-1", new BigDecimal("100.00"), LocalDate.of(2026, 1, 10));
        Transaction b1 = tx("ACC-1", new BigDecimal("100.00"), LocalDate.of(2026, 1, 10));

        var outcome = reconciliationService.match(
                List.of(a1, a2), List.of(b1), new BigDecimal("0.00"), 0);

        assertThat(outcome.matchedPairs()).hasSize(1);
        assertThat(outcome.exceptionsA()).hasSize(1);
        assertThat(outcome.exceptionsB()).isEmpty();
    }
}
