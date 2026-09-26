package com.zentrox.ledger.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One generated report (FRD S6.1), whether produced on demand or by the
 * scheduled job.
 *
 * The rendered document lives in {@code content} rather than object storage:
 * these documents are small and keeping them in Postgres is what lets the whole
 * platform run on a free tier with no S3 credentials.
 *
 * Report *history* is served through {@link com.zentrox.ledger.repository.ReportRunSummary},
 * a projection that selects every column except {@code content}. Marking the
 * field {@code @Basic(fetch = LAZY)} would not have achieved that: lazy loading
 * of a basic attribute silently does nothing without Hibernate bytecode
 * enhancement, so a history page would quietly load every stored document.
 */
@Entity
@Table(name = "report_run", indexes = {
        @Index(name = "idx_report_run_created_at", columnList = "created_at"),
        @Index(name = "idx_report_run_type_status", columnList = "report_type,status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportRun {

    @Id
    @GeneratedValue
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false, length = 50)
    private ReportType reportType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReportFormat format;

    /** Null for reports that span every account. */
    @Column(length = 100)
    private String account;

    @Column(name = "period_from")
    private LocalDate periodFrom;

    @Column(name = "period_to")
    private LocalDate periodTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportStatus status;

    /** Username that requested it, or "scheduler" for the cron-triggered run. */
    @Column(name = "requested_by", nullable = false, length = 100)
    private String requestedBy;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    /**
     * The rendered document, stored as {@code BYTEA} exactly as
     * V4__report_runs.sql declares it.
     *
     * Two things here are deliberate and both were found by a failing test rather
     * than by inspection:
     *  - No {@code @Lob}. On PostgreSQL that maps a byte[] to an {@code oid} large
     *    object, not {@code BYTEA}, so {@code ddl-auto: validate} would disagree
     *    with the migration; and the {@code blob} DDL it generates is rejected
     *    outright by H2's PostgreSQL compatibility mode used in the test profile.
     *  - An explicit {@code columnDefinition} rather than a {@code length}.
     *    Hibernate promotes a VARBINARY longer than the dialect's maximum back to
     *    a LOB type, which reintroduces the same problem. Naming the type is
     *    dialect-coupled, but this project is Postgres-only by design (every
     *    migration is Postgres DDL) and H2 accepts {@code BYTEA} as an alias in
     *    PostgreSQL mode.
     */
    @Column(name = "content", columnDefinition = "bytea")
    private byte[] content;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = ReportStatus.PENDING;
        }
    }
}
