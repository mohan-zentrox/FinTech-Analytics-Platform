package com.zentrox.ledger.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted result row of a reconciliation run (see ReconciliationService).
 * For MATCHED rows both transactionId and matchedTransactionId are set.
 * For EXCEPTION rows only transactionId is set (the unmatched transaction);
 * matchedTransactionId is null.
 */
@Entity
@Table(name = "reconciliation_match", indexes = {
        @Index(name = "idx_recon_run_id", columnList = "run_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReconciliationMatch {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "matched_transaction_id")
    private UUID matchedTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 20)
    private MatchType matchType;

    @Column(name = "amount_delta", precision = 19, scale = 4)
    private BigDecimal amountDelta;

    @Column(name = "date_delta_days")
    private Integer dateDeltaDays;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
