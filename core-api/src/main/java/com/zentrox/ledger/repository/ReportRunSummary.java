package com.zentrox.ledger.repository;

import com.zentrox.ledger.entity.ReportFormat;
import com.zentrox.ledger.entity.ReportStatus;
import com.zentrox.ledger.entity.ReportType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Spring Data interface projection over report_run that omits the {@code content}
 * column.
 *
 * This is what makes a report-history page cheap: Spring Data narrows the
 * generated SELECT to exactly these accessors, so listing 100 runs does not pull
 * 100 rendered documents out of the database and through the JVM.
 */
public interface ReportRunSummary {

    UUID getId();

    ReportType getReportType();

    ReportFormat getFormat();

    String getAccount();

    LocalDate getPeriodFrom();

    LocalDate getPeriodTo();

    ReportStatus getStatus();

    String getRequestedBy();

    String getFileName();

    String getContentType();

    Long getSizeBytes();

    String getErrorMessage();

    Instant getCreatedAt();

    Instant getCompletedAt();
}
