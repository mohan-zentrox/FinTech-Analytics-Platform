package com.zentrox.ledger.reports;

import com.zentrox.ledger.entity.ReportFormat;

/**
 * Renders a {@link ReportData} into a concrete document. One implementation per
 * {@link ReportFormat}; {@link ReportGenerationService} picks the right one by
 * format, so adding CSV or HTML output later means adding a bean, not editing
 * the service.
 */
public interface ReportRenderer {

    ReportFormat format();

    byte[] render(ReportData data);
}
