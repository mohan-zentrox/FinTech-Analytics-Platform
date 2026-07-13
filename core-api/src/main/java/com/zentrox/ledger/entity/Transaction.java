package com.zentrox.ledger.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Canonical ledger transaction. Rows are sourced either from manual entry,
 * CSV import (see CsvImportService) or, in future, accounting-source
 * connectors (see connector.* scaffolding).
 *
 * (source, externalId) is unique so that re-importing the same upstream
 * record is a no-op (idempotent import).
 */
@Entity
@Table(
        name = "transactions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_transactions_source_external_id",
                columnNames = {"source", "external_id"}
        ),
        indexes = {
                @Index(name = "idx_transactions_account", columnList = "account"),
                @Index(name = "idx_transactions_posted_date", columnList = "posted_date"),
                @Index(name = "idx_transactions_status", columnList = "status")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue
    private UUID id;

    /** Origin system, e.g. "manual", "csv-import", "quickbooks", "xero". */
    @Column(nullable = false, length = 50)
    private String source;

    /** Account identifier/number this transaction belongs to. */
    @Column(nullable = false, length = 100)
    private String account;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** ISO-4217 currency code, e.g. USD. */
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "posted_date", nullable = false)
    private LocalDate postedDate;

    @Column(length = 500)
    private String description;

    @Column(length = 100)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    /**
     * Identifier assigned by the source system. Unique per source; used to
     * de-duplicate CSV imports and future connector syncs.
     */
    @Column(name = "external_id", nullable = false, length = 150)
    private String externalId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) {
            status = TransactionStatus.PENDING;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
