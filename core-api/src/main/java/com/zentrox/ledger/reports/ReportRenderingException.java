package com.zentrox.ledger.reports;

/**
 * Wraps a failure inside a document library (OpenPDF / POI) so
 * ReportGenerationService can record it on the report_run row instead of
 * letting a library-specific checked exception leak through the service API.
 */
public class ReportRenderingException extends RuntimeException {

    public ReportRenderingException(String message, Throwable cause) {
        super(message, cause);
    }
}
