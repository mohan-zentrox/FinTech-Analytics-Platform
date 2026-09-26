package com.zentrox.ledger.reports;

import com.zentrox.ledger.entity.ReportFormat;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renderer tests read the produced bytes back rather than asserting on a byte
 * count: a document that is "non-empty" but structurally broken is exactly the
 * failure mode that matters for a report a finance team receives.
 */
class ReportRendererTest {

    private final PdfReportRenderer pdfRenderer = new PdfReportRenderer();
    private final XlsxReportRenderer xlsxRenderer = new XlsxReportRenderer();

    private ReportData sample() {
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Total inflow", "1000.00");
        summary.put("Transactions", "3");

        return new ReportData(
                "Cash Flow Statement",
                "Account: ACC-1   |   Period: 2026-01-01 to 2026-02-28",
                summary,
                List.of("Month", "Inflow", "Outflow", "Net"),
                List.of(ReportData.Alignment.LEFT, ReportData.Alignment.RIGHT,
                        ReportData.Alignment.RIGHT, ReportData.Alignment.RIGHT),
                List.of(
                        List.of("2026-01", "1000.00", "400.00", "600.00"),
                        List.of("2026-02", "0.00", "250.50", "-250.50")));
    }

    // -------------------------------------------------------------------- PDF

    @Test
    void pdfRendererProducesAWellFormedPdf() {
        byte[] pdf = pdfRenderer.render(sample());

        assertThat(pdfRenderer.format()).isEqualTo(ReportFormat.PDF);
        assertThat(pdf).isNotEmpty();
        // %PDF- header and %%EOF trailer: a truncated/aborted document has neither.
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(new String(pdf, StandardCharsets.ISO_8859_1)).contains("%%EOF");
    }

    @Test
    void pdfRendererHandlesAnEmptyTableWithoutFailing() {
        ReportData empty = new ReportData("Empty", "no rows", Map.of(),
                List.of("A", "B"), List.of(ReportData.Alignment.LEFT, ReportData.Alignment.LEFT), List.of());

        byte[] pdf = pdfRenderer.render(empty);

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void pdfRendererToleratesNullCellsAndShortRows() {
        ReportData ragged = new ReportData("Ragged", null, null,
                List.of("A", "B", "C"),
                List.of(ReportData.Alignment.LEFT, ReportData.Alignment.LEFT, ReportData.Alignment.LEFT),
                List.of(java.util.Arrays.asList("only-one", null), List.of("a", "b", "c")));

        byte[] pdf = pdfRenderer.render(ragged);

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    // ------------------------------------------------------------------- XLSX

    @Test
    void xlsxRendererProducesAReadableWorkbookWithTheExpectedCells() throws IOException {
        byte[] xlsx = xlsxRenderer.render(sample());

        assertThat(xlsxRenderer.format()).isEqualTo(ReportFormat.XLSX);

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Cash Flow Statement");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).contains("ACC-1");

            Row headerRow = findRowStartingWith(sheet, "Month");
            assertThat(headerRow).isNotNull();
            assertThat(headerRow.getCell(3).getStringCellValue()).isEqualTo("Net");

            Row firstDataRow = sheet.getRow(headerRow.getRowNum() + 1);
            // The month label stays text; the amounts become real numbers.
            assertThat(firstDataRow.getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(firstDataRow.getCell(0).getStringCellValue()).isEqualTo("2026-01");
            assertThat(firstDataRow.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(firstDataRow.getCell(1).getNumericCellValue()).isEqualTo(1000.0);

            Row secondDataRow = sheet.getRow(headerRow.getRowNum() + 2);
            assertThat(secondDataRow.getCell(3).getNumericCellValue()).isEqualTo(-250.50);
        }
    }

    @Test
    void xlsxRendererKeepsDatesAndIdentifiersAsTextSoExcelDoesNotReformatThem() throws IOException {
        ReportData data = new ReportData("Ids", null, Map.of(),
                List.of("Id", "Posted", "Amount"),
                List.of(ReportData.Alignment.LEFT, ReportData.Alignment.LEFT, ReportData.Alignment.RIGHT),
                List.of(List.of("8f14e45f-ceea-467a-9f35-1d2c3e4b5a60", "2026-03-03", "-125.00")));

        byte[] xlsx = xlsxRenderer.render(data);

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row row = sheet.getRow(findRowStartingWith(sheet, "Id").getRowNum() + 1);
            assertThat(row.getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(2).getCellType()).isEqualTo(CellType.NUMERIC);
        }
    }

    @Test
    void xlsxRendererFreezesTheHeaderRow() throws IOException {
        byte[] xlsx = xlsxRenderer.render(sample());

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getPaneInformation()).isNotNull();
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
        }
    }

    @Test
    void xlsxRendererHandlesAnEmptyTableWithoutFailing() throws IOException {
        ReportData empty = new ReportData("Empty", "no rows", Map.of(),
                List.of("A", "B"), List.of(ReportData.Alignment.LEFT, ReportData.Alignment.LEFT), List.of());

        byte[] xlsx = xlsxRenderer.render(empty);

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("Empty");
        }
    }

    @Test
    void xlsxRendererWritesBlankCellsForMissingValues() throws IOException {
        ReportData ragged = new ReportData("Ragged", null, Map.of(),
                List.of("A", "B"), List.of(ReportData.Alignment.LEFT, ReportData.Alignment.LEFT),
                List.of(List.of("only-one")));

        byte[] xlsx = xlsxRenderer.render(ragged);

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row row = sheet.getRow(findRowStartingWith(sheet, "A").getRowNum() + 1);
            Cell missing = row.getCell(1);
            assertThat(missing.getCellType()).isEqualTo(CellType.BLANK);
        }
    }

    private Row findRowStartingWith(Sheet sheet, String firstCellValue) {
        for (Row row : sheet) {
            Cell cell = row.getCell(0);
            if (cell != null && cell.getCellType() == CellType.STRING
                    && firstCellValue.equals(cell.getStringCellValue())) {
                return row;
            }
        }
        return null;
    }
}
