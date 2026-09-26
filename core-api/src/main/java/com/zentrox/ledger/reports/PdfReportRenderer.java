package com.zentrox.ledger.reports;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.zentrox.ledger.entity.ReportFormat;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

/**
 * PDF renderer (FRD S6.1), built on OpenPDF - the LGPL/MPL-licensed fork of
 * iText 4, chosen so report generation carries no commercial licence obligation.
 */
@Component
public class PdfReportRenderer implements ReportRenderer {

    private static final Color HEADER_BACKGROUND = new Color(0x1D, 0x4E, 0xD8);
    private static final Color ZEBRA_BACKGROUND = new Color(0xF1, 0xF5, 0xF9);

    @Override
    public ReportFormat format() {
        return ReportFormat.PDF;
    }

    @Override
    public byte[] render(ReportData data) {
        // Landscape: these tables are wide, and A4 portrait truncates the amount columns.
        Document document = new Document(PageSize.A4.rotate(), 36, 36, 42, 36);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            PdfWriter.getInstance(document, out);
            document.open();

            document.add(titleParagraph(data.title()));
            if (data.subtitle() != null && !data.subtitle().isBlank()) {
                document.add(subtitleParagraph(data.subtitle()));
            }
            if (data.summary() != null && !data.summary().isEmpty()) {
                document.add(summaryTable(data.summary()));
            }
            document.add(bodyTable(data));
        } catch (DocumentException e) {
            throw new ReportRenderingException("Failed to render PDF report: " + data.title(), e);
        } finally {
            if (document.isOpen()) {
                document.close();
            }
        }
        return out.toByteArray();
    }

    private Paragraph titleParagraph(String title) {
        Paragraph p = new Paragraph(title, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, Color.BLACK));
        p.setSpacingAfter(4f);
        return p;
    }

    private Paragraph subtitleParagraph(String subtitle) {
        Paragraph p = new Paragraph(subtitle, FontFactory.getFont(FontFactory.HELVETICA, 10, Color.DARK_GRAY));
        p.setSpacingAfter(14f);
        return p;
    }

    private PdfPTable summaryTable(Map<String, String> summary) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(45);
        table.setHorizontalAlignment(Element.ALIGN_LEFT);
        table.setSpacingAfter(16f);

        Font labelFont = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.DARK_GRAY);
        Font valueFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.BLACK);

        for (Map.Entry<String, String> entry : summary.entrySet()) {
            table.addCell(borderlessCell(new Phrase(entry.getKey(), labelFont), Element.ALIGN_LEFT));
            table.addCell(borderlessCell(new Phrase(entry.getValue(), valueFont), Element.ALIGN_RIGHT));
        }
        return table;
    }

    private PdfPTable bodyTable(ReportData data) {
        PdfPTable table = new PdfPTable(data.columns().size());
        table.setWidthPercentage(100);
        // Repeat the header row on every page so a multi-page table stays readable.
        table.setHeaderRows(1);

        Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);
        Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.BLACK);

        for (int i = 0; i < data.columns().size(); i++) {
            PdfPCell cell = new PdfPCell(new Phrase(data.columns().get(i), headerFont));
            cell.setBackgroundColor(HEADER_BACKGROUND);
            cell.setPadding(5f);
            cell.setHorizontalAlignment(alignmentOf(data, i));
            table.addCell(cell);
        }

        if (data.rows().isEmpty()) {
            PdfPCell empty = new PdfPCell(new Phrase("No data for this period.", cellFont));
            empty.setColspan(data.columns().size());
            empty.setPadding(10f);
            empty.setHorizontalAlignment(Element.ALIGN_CENTER);
            table.addCell(empty);
            return table;
        }

        int rowIndex = 0;
        for (List<String> row : data.rows()) {
            boolean zebra = rowIndex++ % 2 == 1;
            for (int i = 0; i < data.columns().size(); i++) {
                String value = i < row.size() ? row.get(i) : "";
                PdfPCell cell = new PdfPCell(new Phrase(value == null ? "" : value, cellFont));
                cell.setPadding(4f);
                cell.setHorizontalAlignment(alignmentOf(data, i));
                if (zebra) {
                    cell.setBackgroundColor(ZEBRA_BACKGROUND);
                }
                table.addCell(cell);
            }
        }
        return table;
    }

    private int alignmentOf(ReportData data, int columnIndex) {
        return data.columnAlignments().get(columnIndex) == ReportData.Alignment.RIGHT
                ? Element.ALIGN_RIGHT
                : Element.ALIGN_LEFT;
    }

    private PdfPCell borderlessCell(Phrase phrase, int alignment) {
        PdfPCell cell = new PdfPCell(phrase);
        cell.setBorder(0);
        cell.setPadding(3f);
        cell.setHorizontalAlignment(alignment);
        return cell;
    }
}
