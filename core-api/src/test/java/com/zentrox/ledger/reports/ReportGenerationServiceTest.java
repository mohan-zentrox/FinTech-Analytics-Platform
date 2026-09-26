package com.zentrox.ledger.reports;

import com.zentrox.ledger.dto.reports.ReportGenerateRequest;
import com.zentrox.ledger.entity.ReportFormat;
import com.zentrox.ledger.entity.ReportRun;
import com.zentrox.ledger.entity.ReportStatus;
import com.zentrox.ledger.entity.ReportType;
import com.zentrox.ledger.entity.Transaction;
import com.zentrox.ledger.entity.TransactionStatus;
import com.zentrox.ledger.exception.ResourceNotFoundException;
import com.zentrox.ledger.repository.FraudAlertRepository;
import com.zentrox.ledger.repository.ReconciliationMatchRepository;
import com.zentrox.ledger.repository.ReportRunRepository;
import com.zentrox.ledger.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportGenerationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-15T09:30:00Z");

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private ReconciliationMatchRepository reconciliationMatchRepository;
    @Mock
    private FraudAlertRepository fraudAlertRepository;
    @Mock
    private ReportRunRepository reportRunRepository;

    private ReportProperties properties;
    private ReportGenerationService service;

    @BeforeEach
    void setUp() {
        properties = new ReportProperties();
        service = new ReportGenerationService(
                transactionRepository, reconciliationMatchRepository, fraudAlertRepository,
                reportRunRepository, properties, Clock.fixed(NOW, ZoneOffset.UTC),
                List.of(new PdfReportRenderer(), new XlsxReportRenderer()));

        when(reportRunRepository.save(any(ReportRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.findByAccountAndPostedDateBetween(any(), any(), any())).thenReturn(List.of());
        when(transactionRepository.findByPostedDateBetween(any(), any())).thenReturn(List.of());
        when(reconciliationMatchRepository.findByMatchTypeAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of());
        when(transactionRepository.findAllById(any())).thenReturn(List.of());
        when(fraudAlertRepository.findByDetectedAtBetween(any(), any())).thenReturn(List.of());
        when(fraudAlertRepository.findByAccountAndDetectedAtBetween(any(), any(), any())).thenReturn(List.of());
    }

    private Transaction tx(String amount, LocalDate date) {
        return Transaction.builder()
                .id(UUID.randomUUID()).source("manual").account("ACC-1")
                .amount(new BigDecimal(amount)).currency("USD").postedDate(date)
                .status(TransactionStatus.POSTED).externalId(UUID.randomUUID().toString())
                .build();
    }

    @Test
    void generatesACompletedPdfRunWithFileMetadata() {
        when(transactionRepository.findByAccountAndPostedDateBetween(any(), any(), any()))
                .thenReturn(List.of(tx("1000.00", LocalDate.of(2026, 6, 10))));

        ReportRun run = service.generate(new ReportGenerateRequest(
                ReportType.CASH_FLOW, ReportFormat.PDF, "ACC-1",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)), "t2-lead");

        assertThat(run.getStatus()).isEqualTo(ReportStatus.COMPLETED);
        assertThat(run.getContentType()).isEqualTo("application/pdf");
        assertThat(run.getFileName()).isEqualTo("cash-flow_ACC-1_2026-06-01_2026-06-30.pdf");
        assertThat(run.getSizeBytes()).isEqualTo(run.getContent().length);
        assertThat(run.getRequestedBy()).isEqualTo("t2-lead");
        assertThat(run.getCreatedAt()).isEqualTo(NOW);
        assertThat(run.getCompletedAt()).isEqualTo(NOW);
        assertThat(run.getErrorMessage()).isNull();
    }

    @Test
    void generatesXlsxWhenRequested() {
        ReportRun run = service.generate(new ReportGenerateRequest(
                ReportType.CASH_FLOW, ReportFormat.XLSX, "ACC-1",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)), "t2-lead");

        assertThat(run.getFormat()).isEqualTo(ReportFormat.XLSX);
        assertThat(run.getFileName()).endsWith(".xlsx");
        assertThat(run.getContentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @Test
    void fallsBackToTheConfiguredDefaultFormat() {
        properties.setDefaultFormat(ReportFormat.XLSX);

        ReportRun run = service.generate(new ReportGenerateRequest(
                ReportType.CASH_FLOW, null, "ACC-1",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)), "t2-lead");

        assertThat(run.getFormat()).isEqualTo(ReportFormat.XLSX);
    }

    @Test
    void defaultsThePeriodToThePreviousWholeCalendarMonth() {
        ReportRun run = service.generate(
                new ReportGenerateRequest(ReportType.CASH_FLOW, ReportFormat.PDF, "ACC-1", null, null),
                "t2-lead");

        // Clock is 2026-07-15, so the previous whole month is June.
        assertThat(run.getPeriodFrom()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(run.getPeriodTo()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void rejectsAnInvertedPeriod() {
        assertThatThrownBy(() -> service.generate(new ReportGenerateRequest(
                ReportType.CASH_FLOW, ReportFormat.PDF, "ACC-1",
                LocalDate.of(2026, 6, 30), LocalDate.of(2026, 6, 1)), "t2-lead"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("periodFrom must not be after periodTo");
    }

    @Test
    void namesAllAccountReportsExplicitlyAndSanitisesAccountIdsInFilenames() {
        ReportRun allAccounts = service.generate(new ReportGenerateRequest(
                ReportType.CASH_FLOW, ReportFormat.PDF, null,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)), "t2-lead");
        assertThat(allAccounts.getFileName()).isEqualTo("cash-flow_all-accounts_2026-06-01_2026-06-30.pdf");

        ReportRun hostile = service.generate(new ReportGenerateRequest(
                ReportType.CASH_FLOW, ReportFormat.PDF, "../../etc/passwd",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)), "t2-lead");
        assertThat(hostile.getFileName())
                .isEqualTo("cash-flow_etc-passwd_2026-06-01_2026-06-30.pdf")
                .doesNotContain("/")
                .doesNotContain("\\")
                .doesNotContain("..");

        ReportRun onlyPunctuation = service.generate(new ReportGenerateRequest(
                ReportType.CASH_FLOW, ReportFormat.PDF, "///",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)), "t2-lead");
        assertThat(onlyPunctuation.getFileName()).isEqualTo("cash-flow_account_2026-06-01_2026-06-30.pdf");
    }

    @Test
    void recordsAFailedRunWithItsErrorInsteadOfLosingTheFailure() {
        ReportRenderer exploding = new ReportRenderer() {
            @Override
            public ReportFormat format() {
                return ReportFormat.PDF;
            }

            @Override
            public byte[] render(ReportData data) {
                throw new ReportRenderingException("font metrics unavailable", new RuntimeException("boom"));
            }
        };
        ReportGenerationService failing = new ReportGenerationService(
                transactionRepository, reconciliationMatchRepository, fraudAlertRepository,
                reportRunRepository, properties, Clock.fixed(NOW, ZoneOffset.UTC), List.of(exploding));

        ReportRun run = failing.generate(new ReportGenerateRequest(
                ReportType.CASH_FLOW, ReportFormat.PDF, "ACC-1",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)), "t2-lead");

        assertThat(run.getStatus()).isEqualTo(ReportStatus.FAILED);
        assertThat(run.getErrorMessage()).contains("font metrics unavailable");
        assertThat(run.getContent()).isNull();
        verify(reportRunRepository).save(run);
    }

    @Test
    void rejectsAFormatWithNoRegisteredRenderer() {
        ReportGenerationService pdfOnly = new ReportGenerationService(
                transactionRepository, reconciliationMatchRepository, fraudAlertRepository,
                reportRunRepository, properties, Clock.fixed(NOW, ZoneOffset.UTC),
                List.of(new PdfReportRenderer()));

        assertThatThrownBy(() -> pdfOnly.generate(new ReportGenerateRequest(
                ReportType.CASH_FLOW, ReportFormat.XLSX, "ACC-1",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)), "t2-lead"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No renderer registered");
    }

    @Test
    void generatesEveryReportTypeWithoutFailing() {
        for (ReportType type : ReportType.values()) {
            for (ReportFormat format : ReportFormat.values()) {
                ReportRun run = service.generate(new ReportGenerateRequest(
                        type, format, "ACC-1",
                        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)), "t2-qa1");
                assertThat(run.getStatus())
                        .as("%s / %s", type, format)
                        .isEqualTo(ReportStatus.COMPLETED);
                assertThat(run.getContent()).as("%s / %s", type, format).isNotEmpty();
            }
        }
    }

    // ---------------------------------------------------------------- download

    @Test
    void downloadingAReportThatFailedToRenderIsANotFound() {
        UUID id = UUID.randomUUID();
        when(reportRunRepository.findById(id)).thenReturn(Optional.of(ReportRun.builder()
                .id(id).reportType(ReportType.CASH_FLOW).format(ReportFormat.PDF)
                .status(ReportStatus.FAILED).requestedBy("t2-lead").createdAt(NOW).build()));

        assertThatThrownBy(() -> service.getDownloadable(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("no downloadable document");
    }

    @Test
    void downloadingAMissingReportIsANotFound() {
        UUID id = UUID.randomUUID();
        when(reportRunRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDownloadable(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    // --------------------------------------------------------------- retention

    @Test
    void prunesReportsOlderThanTheRetentionWindow() {
        properties.setRetentionDays(30);
        when(reportRunRepository.deleteByCreatedAtBefore(any())).thenReturn(4);

        assertThat(service.pruneOlderThanRetention()).isEqualTo(4);
        verify(reportRunRepository).deleteByCreatedAtBefore(NOW.minusSeconds(30L * 86_400L));
    }

    @Test
    void retentionZeroKeepsReportsForever() {
        properties.setRetentionDays(0);

        assertThat(service.pruneOlderThanRetention()).isZero();
        verify(reportRunRepository, never()).deleteByCreatedAtBefore(any());
    }
}
