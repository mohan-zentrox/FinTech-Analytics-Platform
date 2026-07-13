package com.zentrox.ledger.service;

import com.zentrox.ledger.aspect.Audited;
import com.zentrox.ledger.dto.importcsv.CsvImportResult;
import com.zentrox.ledger.dto.importcsv.RowError;
import com.zentrox.ledger.entity.Transaction;
import com.zentrox.ledger.entity.TransactionStatus;
import com.zentrox.ledger.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses an uploaded CSV into canonical Transaction rows and persists them,
 * de-duplicating by (source, externalId) so re-uploading the same file (or
 * an overlapping export) is idempotent.
 *
 * Expected header (case-insensitive, order-independent):
 * source,account,amount,currency,postedDate,description,category,status,externalId
 * - source, account, amount, currency, postedDate, externalId are required.
 * - postedDate must be ISO-8601 (yyyy-MM-dd).
 * - status defaults to PENDING when blank/absent.
 */
@Service
@RequiredArgsConstructor
public class CsvImportService {

    private static final List<String> REQUIRED_HEADERS = List.of(
            "source", "account", "amount", "currency", "postedDate", "externalId");

    private final TransactionRepository transactionRepository;

    @Audited(action = "IMPORT", entity = "Transaction")
    @Transactional
    public CsvImportResult importCsv(MultipartFile file) {
        int imported = 0;
        int skipped = 0;
        List<RowError> errors = new ArrayList<>();
        Set<String> seenInBatch = new HashSet<>();

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreSurroundingSpaces(true)
                .setIgnoreHeaderCase(true)
                .setTrim(true)
                .build();

        try (var reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {

            for (String required : REQUIRED_HEADERS) {
                if (!parser.getHeaderNames().stream().anyMatch(h -> h.equalsIgnoreCase(required))) {
                    throw new IllegalArgumentException("CSV is missing required column: " + required);
                }
            }

            for (CSVRecord record : parser) {
                // CSVParser's record number is 1-based over data rows when header is consumed separately.
                long rowNumber = record.getRecordNumber();
                try {
                    Transaction tx = parseRow(record);
                    String dedupeKey = tx.getSource() + "::" + tx.getExternalId();

                    if (seenInBatch.contains(dedupeKey)
                            || transactionRepository.existsBySourceAndExternalId(tx.getSource(), tx.getExternalId())) {
                        skipped++;
                        continue;
                    }

                    seenInBatch.add(dedupeKey);
                    transactionRepository.save(tx);
                    imported++;
                } catch (Exception rowEx) {
                    errors.add(new RowError((int) rowNumber, rowEx.getMessage()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded CSV file", e);
        }

        return new CsvImportResult(imported, skipped, errors.size(), errors);
    }

    private Transaction parseRow(CSVRecord record) {
        String source = requireField(record, "source");
        String account = requireField(record, "account");
        String currency = requireField(record, "currency");
        String externalId = requireField(record, "externalId");

        BigDecimal amount;
        try {
            amount = new BigDecimal(requireField(record, "amount"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid amount value: '" + get(record, "amount") + "'");
        }

        LocalDate postedDate;
        try {
            postedDate = LocalDate.parse(requireField(record, "postedDate"));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Invalid postedDate '" + get(record, "postedDate") + "', expected yyyy-MM-dd");
        }

        String description = get(record, "description");
        String category = get(record, "category");

        TransactionStatus status = TransactionStatus.PENDING;
        String statusRaw = get(record, "status");
        if (statusRaw != null && !statusRaw.isBlank()) {
            try {
                status = TransactionStatus.valueOf(statusRaw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid status value: '" + statusRaw + "'");
            }
        }

        if (currency.length() != 3) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO-4217 code, got: '" + currency + "'");
        }

        return Transaction.builder()
                .source(source)
                .account(account)
                .amount(amount)
                .currency(currency.toUpperCase())
                .postedDate(postedDate)
                .description(description)
                .category(category)
                .status(status)
                .externalId(externalId)
                .build();
    }

    private String get(CSVRecord record, String header) {
        if (!record.isMapped(header) || !record.isSet(header)) {
            return null;
        }
        String value = record.get(header);
        return value == null ? null : value.trim();
    }

    private String requireField(CSVRecord record, String header) {
        String value = get(record, header);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + header);
        }
        return value;
    }
}
