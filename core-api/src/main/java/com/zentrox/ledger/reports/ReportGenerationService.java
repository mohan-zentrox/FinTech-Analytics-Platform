package com.zentrox.ledger.reports;

import org.springframework.stereotype.Service;

/**
 * SCAFFOLD ONLY - no business logic implemented yet.
 *
 * TODO (FRD S6.1 "Scheduled Reporting"): generate periodic PDF/Excel
 * reports (e.g. monthly cash-flow statement, reconciliation exception
 * report) and deliver via email / object storage.
 * TODO: pick a PDF library (e.g. OpenPDF/iText) and an Excel library (e.g.
 * Apache POI); template ownership sits with T2-BA1 (Business Analyst).
 * TODO: wire a @Scheduled(cron = ...) trigger here once the report
 * templates and delivery channel (S3? SMTP?) are decided; consider
 * delegating heavy aggregation to analytics-service instead of
 * recomputing pandas-equivalent logic in Java.
 * TODO: persist a `report_run` table (id, reportType, status, generatedAt,
 * storageUrl) so report history is queryable from the frontend.
 */
@Service
public class ReportGenerationService {

    public void generateMonthlyCashFlowReport(String accountId) {
        throw new UnsupportedOperationException(
                "ReportGenerationService.generateMonthlyCashFlowReport is not implemented - see FRD S6.1");
    }
}
