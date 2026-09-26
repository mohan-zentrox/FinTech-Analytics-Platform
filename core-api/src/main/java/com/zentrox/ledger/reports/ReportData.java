package com.zentrox.ledger.reports;

import java.util.List;
import java.util.Map;

/**
 * Format-independent model of a rendered report: a title block, an optional
 * summary key/value section, and one table.
 *
 * Splitting "what the report says" from "how it is drawn" is what keeps the PDF
 * and XLSX renderers interchangeable and lets the data assembly be unit-tested
 * without parsing a binary document.
 *
 * @param columnAlignments per-column alignment hints; RIGHT for numeric columns
 *                         so both renderers line figures up the same way.
 */
public record ReportData(
        String title,
        String subtitle,
        Map<String, String> summary,
        List<String> columns,
        List<Alignment> columnAlignments,
        List<List<String>> rows
) {
    public enum Alignment {
        LEFT,
        RIGHT
    }

    public ReportData {
        if (columns.size() != columnAlignments.size()) {
            throw new IllegalArgumentException(
                    "columnAlignments must have one entry per column: got "
                            + columnAlignments.size() + " for " + columns.size() + " columns");
        }
    }

    /** Convenience factory for an all-left-aligned table. */
    public static ReportData of(String title, String subtitle, Map<String, String> summary,
                                List<String> columns, List<List<String>> rows) {
        return new ReportData(title, subtitle, summary, columns,
                columns.stream().map(c -> Alignment.LEFT).toList(), rows);
    }
}
