package com.zentrox.ledger.service;

import com.zentrox.ledger.dto.importcsv.CsvImportResult;
import com.zentrox.ledger.entity.Transaction;
import com.zentrox.ledger.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CsvImportServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    private CsvImportService csvImportService;

    @BeforeEach
    void setUp() {
        csvImportService = new CsvImportService(transactionRepository);
    }

    @Test
    void importsValidRowsSkipsDuplicatesAndCollectsRowErrors() {
        String csv = String.join("\n",
                "source,account,amount,currency,postedDate,description,category,status,externalId",
                "csv-import,ACC-1,100.50,USD,2026-01-05,Office supplies,opex,PENDING,EXT-1",
                "csv-import,ACC-1,-50.00,USD,2026-01-06,Refund,revenue,POSTED,EXT-2",
                "csv-import,ACC-1,75.00,USD,2026-01-07,Duplicate row,opex,PENDING,EXT-DUP",
                "csv-import,ACC-1,not-a-number,USD,2026-01-08,Bad amount,opex,PENDING,EXT-3",
                "csv-import,ACC-1,10.00,USD,not-a-date,Bad date,opex,PENDING,EXT-4"
        );
        MockMultipartFile file = new MockMultipartFile(
                "file", "transactions.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        // EXT-DUP already exists in the DB; everything else is new.
        when(transactionRepository.existsBySourceAndExternalId("csv-import", "EXT-DUP")).thenReturn(true);
        when(transactionRepository.existsBySourceAndExternalId(eq("csv-import"), argThat(id -> !"EXT-DUP".equals(id))))
                .thenReturn(false);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        CsvImportResult result = csvImportService.importCsv(file);

        assertThat(result.imported()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.errored()).isEqualTo(2);
        assertThat(result.errors()).hasSize(2);
        verify(transactionRepository, times(2)).save(any(Transaction.class));
    }

    @Test
    void skipsRowsAlreadyImportedInTheSameBatch() {
        String csv = String.join("\n",
                "source,account,amount,currency,postedDate,description,category,status,externalId",
                "manual,ACC-1,10.00,USD,2026-01-01,First,opex,,EXT-SAME",
                "manual,ACC-1,10.00,USD,2026-01-01,Repeated in file,opex,,EXT-SAME"
        );
        MockMultipartFile file = new MockMultipartFile(
                "file", "transactions.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        when(transactionRepository.existsBySourceAndExternalId(anyString(), anyString())).thenReturn(false);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        CsvImportResult result = csvImportService.importCsv(file);

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.errored()).isZero();
    }

    @Test
    void rejectsCsvMissingRequiredColumns() {
        String csv = String.join("\n", "account,amount", "ACC-1,10.00");
        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> csvImportService.importCsv(file));
    }
}
