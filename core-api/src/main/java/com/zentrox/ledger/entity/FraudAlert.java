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
 * A single anomaly finding produced by a fraud scan (FRD S5.4).
 *
 * Unique on (transactionId, ruleId): re-scanning an overlapping window
 * refreshes the existing finding's score instead of accumulating duplicates,
 * which is what makes the scan endpoint safe to call repeatedly (and from a
 * scheduler).
 */
@Entity
@Table(name = "fraud_alert",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_fraud_alert_transaction_rule",
                columnNames = {"transaction_id", "rule_id"}),
        indexes = {
                @Index(name = "idx_fraud_alert_account_status", columnList = "account,status"),
                @Index(name = "idx_fraud_alert_scan_id", columnList = "scan_id"),
                @Index(name = "idx_fraud_alert_detected_at", columnList = "detected_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FraudAlert {

    @Id
    @GeneratedValue
    private UUID id;

    /** Groups every alert produced by one scan run. */
    @Column(name = "scan_id", nullable = false)
    private UUID scanId;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    /** Denormalised from the transaction so alert lists can filter by account without a join. */
    @Column(nullable = false, length = 100)
    private String account;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_id", nullable = false, length = 50)
    private FraudRule ruleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AlertSeverity severity;

    /** Rule-specific anomaly score; higher is more suspicious. */
    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal score;

    /** Human-readable explanation shown to the reviewing analyst. */
    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AlertStatus status;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    @PrePersist
    void onCreate() {
        if (detectedAt == null) {
            detectedAt = Instant.now();
        }
        if (status == null) {
            status = AlertStatus.OPEN;
        }
    }
}
