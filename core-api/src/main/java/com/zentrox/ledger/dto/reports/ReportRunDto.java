package com.zentrox.ledger.dto.reports;

import com.zentrox.ledger.entity.ReportFormat;
import com.zentrox.ledger.entity.ReportRun;
import com.zentrox.ledger.entity.ReportStatus;
import com.zentrox.ledger.entity.ReportType;
import com.zentrox.ledger.repository.ReportRunSummary;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Report history row. Deliberately excludes the document bytes - clients fetch
 * those separately from GET /api/reports/{id}/download.
 */
public record ReportRunDto(
        UUID id,
        ReportType reportType,
        ReportFormat format,
        String account,
        LocalDate periodFrom,
        LocalDate periodTo,
        ReportStatus status,
        String requestedBy,
        String fileName,
        String contentType,
        Long sizeBytes,
        String errorMessage,
        Instant createdAt,
        Instant completedAt
) {
    public static ReportRunDto from(ReportRun run) {
        return new ReportRunDto(
                run.getId(), run.getReportType(), run.getFormat(), run.getAccount(),
                run.getPeriodFrom(), run.getPeriodTo(), run.getStatus(), run.getRequestedBy(),
                run.getFileName(), run.getContentType(), run.getSizeBytes(), run.getErrorMessage(),
                run.getCreatedAt(), run.getCompletedAt());
    }

    /** Built from the content-free projection used by report history. */
    public static ReportRunDto from(ReportRunSummary summary) {
        return new ReportRunDto(
                summary.getId(), summary.getReportType(), summary.getFormat(), summary.getAccount(),
                summary.getPeriodFrom(), summary.getPeriodTo(), summary.getStatus(), summary.getRequestedBy(),
                summary.getFileName(), summary.getContentType(), summary.getSizeBytes(), summary.getErrorMessage(),
                summary.getCreatedAt(), summary.getCompletedAt());
    }
}
