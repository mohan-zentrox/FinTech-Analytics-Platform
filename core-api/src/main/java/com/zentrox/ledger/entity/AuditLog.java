package com.zentrox.ledger.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit trail. Rows are never updated or deleted by the
 * application; only inserted by {@link com.zentrox.ledger.aspect.AuditLoggingAspect}
 * (or directly by services) on every mutating request.
 */
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_log_entity", columnList = "entity,entity_id"),
        @Index(name = "idx_audit_log_timestamp", columnList = "timestamp")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue
    private UUID id;

    /** Username (or "system") that performed the action. */
    @Column(nullable = false, length = 100)
    private String actor;

    /** e.g. CREATE, UPDATE, DELETE, IMPORT, RECONCILE. */
    @Column(nullable = false, length = 50)
    private String action;

    /** Entity type affected, e.g. "Transaction". */
    @Column(nullable = false, length = 100)
    private String entity;

    /** Primary key (as string) of the affected entity, if applicable. */
    @Column(name = "entity_id", length = 100)
    private String entityId;

    @Column(length = 1000)
    private String details;

    @Column(nullable = false)
    private Instant timestamp;

    @PrePersist
    void onCreate() {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }
}
