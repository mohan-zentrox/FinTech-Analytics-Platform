package com.zentrox.ledger.dto.reports;

import com.zentrox.ledger.entity.ReportFormat;
import com.zentrox.ledger.entity.ReportType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * POST /api/reports/generate.
 *
 * `format` defaults to ledger.reports.default-format when omitted, and
 * periodFrom/periodTo default to the previous whole calendar month - the period
 * the scheduled monthly job reports on, so an on-demand call with no dates
 * reproduces exactly what the cron job would have produced.
 */
public record ReportGenerateRequest(
        @NotNull ReportType reportType,
        ReportFormat format,
        @Size(max = 100) String account,
        LocalDate periodFrom,
        LocalDate periodTo
) {
}
