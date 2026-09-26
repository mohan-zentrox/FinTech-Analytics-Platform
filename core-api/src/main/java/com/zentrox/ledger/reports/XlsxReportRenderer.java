package com.zentrox.ledger.reports;

import com.zentrox.ledger.entity.ReportFormat;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Excel (.xlsx) renderer (FRD S6.1) built on Apache POI.
 *
 * Numeric-looking cells are written as real numbers, not strings, so the
 * spreadsheet a finance analyst receives is immediately usable in formulas and
 * pivot tables - the whole point of offering XLSX alongside PDF.
 */
@Component
public class XlsxReportRenderer implements ReportRenderer {

    private static final String SHEET_NAME = "Report";
    private static final String NUMBER_FORMAT = "#,##0.00";

    @Override
    public ReportFormat format() {
        return ReportFormat.XLSX;
    }

    @Override
    public byte[] render(ReportData data) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet(SHEET_NAME);
            CellStyle titleStyle = titleStyle(workbook);
            CellStyle subtitleStyle = subtitleStyle(workbook);
            CellStyle labelStyle = labelStyle(workbook);
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle numberStyle = numberStyle(workbook);

            int rowNum = 0;

            Row titleRow = sheet.createRow(rowNum++);
            cell(titleRow, 0, data.title(), titleStyle);
            mergeAcross(sheet, titleRow.getRowNum(), data.columns().size());

            if (data.subtitle() != null && !data.subtitle().isBlank()) {
                Row subtitleRow = sheet.createRow(rowNum++);
                cell(subtitleRow, 0, data.subtitle(), subtitleStyle);
                mergeAcross(sheet, subtitleRow.getRowNum(), data.columns().size());
            }

            rowNum++; // spacer

            if (data.summary() != null && !data.summary().isEmpty()) {
                for (Map.Entry<String, String> entry : data.summary().entrySet()) {
                    Row summaryRow = sheet.createRow(rowNum++);
                    cell(summaryRow, 0, entry.getKey(), labelStyle);
                    writeValue(summaryRow, 1, entry.getValue(), null, numberStyle);
                }
                rowNum++; // spacer
            }

            Row headerRow = sheet.createRow(rowNum++);
            for (int i = 0; i < data.columns().size(); i++) {
                cell(headerRow, i, data.columns().get(i), headerStyle);
            }
            int headerRowNum = headerRow.getRowNum();

            for (List<String> dataRow : data.rows()) {
                Row row = sheet.createRow(rowNum++);
                for (int i = 0; i < data.columns().size(); i++) {
                    String value = i < dataRow.size() ? dataRow.get(i) : "";
                    writeValue(row, i, value, null, numberStyle);
                }
            }

            // A frozen header plus an autofilter is what makes a 5,000-row export
            // actually workable for the analyst receiving it.
            sheet.createFreezePane(0, headerRowNum + 1);
            if (!data.rows().isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(
                        headerRowNum, rowNum - 1, 0, data.columns().size() - 1));
            }
            for (int i = 0; i < data.columns().size(); i++) {
                sheet.autoSizeColumn(i);
                // autoSizeColumn ignores padding; widen slightly so headers are not clipped.
                sheet.setColumnWidth(i, Math.min(sheet.getColumnWidth(i) + 768, 255 * 256));
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ReportRenderingException("Failed to render XLSX report: " + data.title(), e);
        }
    }

    /**
     * Writes `value` as a number when it parses as one, otherwise as text.
     * Currency/percent prefixes and thousands separators are stripped first,
     * since the assembler formats amounts for human reading.
     */
    private void writeValue(Row row, int column, String value, CellStyle textStyle, CellStyle numberStyle) {
        Cell cell = row.createCell(column);
        if (value == null || value.isBlank()) {
            cell.setBlank();
            return;
        }
        Double numeric = asNumber(value);
        if (numeric != null) {
            cell.setCellValue(numeric);
            cell.setCellStyle(numberStyle);
        } else {
            cell.setCellValue(value);
            if (textStyle != null) {
                cell.setCellStyle(textStyle);
            }
        }
    }

    private Double asNumber(String value) {
        String cleaned = value.trim().replace(",", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        // Reject anything that is not a plain signed decimal: dates ("2026-06-01"),
        // ids and enum names must stay text, or Excel mangles them.
        if (!cleaned.matches("[-+]?\\d+(\\.\\d+)?")) {
            return null;
        }
        try {
            return Double.valueOf(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void mergeAcross(Sheet sheet, int rowNum, int columnCount) {
        if (columnCount > 1) {
            sheet.addMergedRegion(new CellRangeAddress(rowNum, rowNum, 0, columnCount - 1));
        }
    }

    private void cell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private CellStyle titleStyle(Workbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        return style;
    }

    private CellStyle subtitleStyle(Workbook workbook) {
        Font font = workbook.createFont();
        font.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        return style;
    }

    private CellStyle labelStyle(Workbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        return style;
    }

    private CellStyle headerStyle(Workbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    private CellStyle numberStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat(NUMBER_FORMAT));
        style.setAlignment(HorizontalAlignment.RIGHT);
        return style;
    }
}
