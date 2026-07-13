package com.zentrox.ledger.service;

import com.zentrox.ledger.dto.transaction.TransactionCreateRequest;
import com.zentrox.ledger.dto.transaction.TransactionUpdateRequest;
import com.zentrox.ledger.entity.Transaction;
import com.zentrox.ledger.entity.TransactionStatus;
import com.zentrox.ledger.exception.DuplicateResourceException;
import com.zentrox.ledger.exception.ResourceNotFoundException;
import com.zentrox.ledger.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(transactionRepository);
    }

    @Test
    void createThrowsWhenSourceAndExternalIdAlreadyExist() {
        TransactionCreateRequest request = new TransactionCreateRequest(
                "csv-import", "ACC-1", new BigDecimal("100.00"), "USD",
                LocalDate.of(2026, 1, 1), "desc", "fees", TransactionStatus.PENDING, "EXT-1");

        when(transactionRepository.existsBySourceAndExternalId("csv-import", "EXT-1")).thenReturn(true);

        assertThatThrownBy(() -> transactionService.create(request))
                .isInstanceOf(DuplicateResourceException.class);

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void createSavesTransactionWhenNotDuplicate() {
        TransactionCreateRequest request = new TransactionCreateRequest(
                "manual", "ACC-1", new BigDecimal("250.50"), "USD",
                LocalDate.of(2026, 2, 1), "desc", "revenue", null, "EXT-2");

        when(transactionRepository.existsBySourceAndExternalId("manual", "EXT-2")).thenReturn(false);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        Transaction result = transactionService.create(request);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getExternalId()).isEqualTo("EXT-2");
        assertThat(captor.getValue().getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(result.getAccount()).isEqualTo("ACC-1");
    }

    @Test
    void getByIdThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(transactionRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.getById(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateAppliesAllMutableFields() {
        UUID id = UUID.randomUUID();
        Transaction existing = Transaction.builder()
                .id(id).source("manual").account("ACC-1").amount(new BigDecimal("10.00"))
                .currency("USD").postedDate(LocalDate.of(2026, 1, 1)).status(TransactionStatus.PENDING)
                .externalId("EXT-3").build();
        when(transactionRepository.findById(id)).thenReturn(Optional.of(existing));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionUpdateRequest update = new TransactionUpdateRequest(
                "ACC-2", new BigDecimal("20.00"), "EUR", LocalDate.of(2026, 3, 1),
                "updated desc", "opex", TransactionStatus.POSTED);

        Transaction result = transactionService.update(id, update);

        assertThat(result.getAccount()).isEqualTo("ACC-2");
        assertThat(result.getAmount()).isEqualByComparingTo("20.00");
        assertThat(result.getCurrency()).isEqualTo("EUR");
        assertThat(result.getStatus()).isEqualTo(TransactionStatus.POSTED);
    }

    @Test
    void deleteThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(transactionRepository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> transactionService.delete(id))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(transactionRepository, never()).deleteById(any());
    }
}
