package com.zentrox.ledger.reports;

import com.zentrox.ledger.aspect.Audited;
import com.zentrox.ledger.dto.reports.ReportGenerateRequest;
import com.zentrox.ledger.entity.FraudAlert;
import com.zentrox.ledger.entity.MatchType;
import com.zentrox.ledger.entity.ReconciliationMatch;
import com.zentrox.ledger.entity.ReportFormat;
import com.zentrox.ledger.entity.ReportRun;
import com.zentrox.ledger.entity.ReportStatus;
import com.zentrox.ledger.entity.ReportType;
import com.zentrox.ledger.entity.Transaction;
import com.zentrox.ledger.exception.ResourceNotFoundException;
import com.zentrox.ledger.repository.FraudAlertRepository;
import com.zentrox.ledger.repository.ReconciliationMatchRepository;
import com.zentrox.ledger.repository.ReportRunRepository;
import com.zentrox.ledger.repository.ReportRunSummary;
import com.zentrox.ledger.repository.TransactionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * FRD S6.1 "Scheduled Reporting" - generates cash-flow, reconciliation-exception
 * and anomaly-alert documents in PDF or Excel and records every run in
 * report_run so report history is queryable from the frontend.
 *
 * A failed render is recorded as a FAILED report_run with its error message
 * rather than being thrown away, because "the month-end report did not arrive"
 * needs to be diagnosable after the fact.
 */
@Service
@Slf4j
public class ReportGenerationService {

    private final TransactionRepository transactionRepository;
    private final ReconciliationMatchRepository reconciliationMatchRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final ReportRunRepository reportRunRepository;
    private final ReportProperties properties;
    private final Clock clock;
    private final Map<ReportFormat, ReportRenderer> renderers = new EnumMap<>(ReportFormat.class);

    public ReportGenerationService(TransactionRepository transactionRepository,
                                   ReconciliationMatchRepository reconciliationMatchRepository,
                                   FraudAlertRepository fraudAlertRepository,
                                   ReportRunRepository reportRunRepository,
                                   ReportProperties properties,
                                   Clock clock,
                                   List<ReportRenderer> rendererBeans) {
        this.transactionRepository = transactionRepository;
        this.reconciliationMatchRepository = reconciliationMatchRepository;
        this.fraudAlertRepository = fraudAlertRepository;
        this.reportRunRepository = reportRunRepository;
        this.properties = properties;
        this.clock = clock;
        for (ReportRenderer renderer : rendererBeans) {
            this.renderers.put(renderer.format(), renderer);
        }
    }

    /**
     * Generates one report synchronously and returns the persisted run.
     *
     * Synchronous by design: these documents take milliseconds to build, and an
     * async job queue would add moving parts (and a worker that free-tier hosting
     * would put to sleep) for no benefit at this volume.
     */
    @Audited(action = "GENERATE", entity = "ReportRun")
    @Transactional
    public ReportRun generate(ReportGenerateRequest request, String requestedBy) {
        ReportFormat format = request.format() == null ? properties.getDefaultFormat() : request.format();
        LocalDate[] period = resolvePeriod(request.periodFrom(), request.periodTo());
        LocalDate from = period[0];
        LocalDate to = period[1];

        if (from.isAfter(to)) {
            throw new IllegalArgumentException("periodFrom must not be after periodTo");
        }

        ReportRenderer renderer = renderers.get(format);
        if (renderer == null) {
            throw new IllegalArgumentException("No renderer registered for format " + format);
        }

        ReportRun run = ReportRun.builder()
                .reportType(request.reportType())
                .format(format)
                .account(request.account())
                .periodFrom(from)
                .periodTo(to)
                .status(ReportStatus.PENDING)
                .requestedBy(requestedBy == null ? "system" : requestedBy)
                .createdAt(clock.instant())
                .build();

        try {
            ReportData data = assemble(request.reportType(), request.account(), from, to);
            byte[] document = renderer.render(data);

            run.setContent(document);
            run.setSizeBytes((long) document.length);
            run.setContentType(format.contentType());
            run.setFileName(fileName(request.reportType(), request.account(), from, to, format));
            run.setStatus(ReportStatus.COMPLETED);
            run.setCompletedAt(clock.instant());
        } catch (RuntimeException e) {
            log.error("Report generation failed: type={} format={} account={} period={}..{}",
                    request.reportType(), format, request.account(), from, to, e);
            run.setStatus(ReportStatus.FAILED);
            run.setErrorMessage(truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 1000));
            run.setCompletedAt(clock.instant());
        }

        return reportRunRepository.save(run);
    }

    @Transactional(readOnly = true)
    public Page<ReportRunSummary> history(ReportType reportType, Pageable pageable) {
        return reportType == null
                ? reportRunRepository.findAllProjectedBy(pageable)
                : reportRunRepository.findByReportType(reportType, pageable);
    }

    @Transactional(readOnly = true)
    public ReportRun getRun(UUID id) {
        return reportRunRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Report run not found: " + id));
    }

    /** Loads a completed run together with its document bytes, for download. */
    @Transactional(readOnly = true)
    public ReportRun getDownloadable(UUID id) {
        ReportRun run = getRun(id);
        if (run.getStatus() != ReportStatus.COMPLETED || run.getContent() == null) {
            throw new ResourceNotFoundException(
                    "Report " + id + " has no downloadable document (status=" + run.getStatus() + ")");
        }
        return run;
    }

    /** Removes stored reports older than the retention window; returns rows deleted. */
    @Transactional
    public int pruneOlderThanRetention() {
        if (properties.getRetentionDays() <= 0) {
            return 0;
        }
        Instant cutoff = clock.instant().minusSeconds(properties.getRetentionDays() * 86_400L);
        int deleted = reportRunRepository.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Pruned {} report run(s) created before {}", deleted, cutoff);
        }
        return deleted;
    }

    /** Accounts with activity in the period - the scope of the scheduled monthly run. */
    @Transactional(readOnly = true)
    public List<String> accountsWithActivity(LocalDate from, LocalDate to) {
        return transactionRepository.findDistinctAccountsBetween(from, to);
    }

    // ---------------------------------------------------------------- internals

    ReportData assemble(ReportType type, String account, LocalDate from, LocalDate to) {
        return switch (type) {
            case CASH_FLOW -> ReportDataAssembler.cashFlow(account, from, to, loadTransactions(account, from, to));
            case RECONCILIATION_EXCEPTIONS -> assembleReconciliationExceptions(account, from, to);
            case FRAUD_ALERTS -> ReportDataAssembler.fraudAlerts(account, from, to, loadAlerts(account, from, to));
        };
    }

    private ReportData assembleReconciliationExceptions(String account, LocalDate from, LocalDate to) {
        List<ReconciliationMatch> exceptions = reconciliationMatchRepository
                .findByMatchTypeAndCreatedAtBetween(MatchType.EXCEPTION, startOfDay(from), endOfDay(to));

        Map<UUID, Transaction> transactionsById = transactionRepository
                .findAllById(exceptions.stream().map(ReconciliationMatch::getTransactionId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Transaction::getId, Function.identity()));

        // An account filter applies to the *transaction*, which is why it is applied
        // here rather than in the query: reconciliation_match itself has no account.
        if (account != null && !account.isBlank()) {
            exceptions = exceptions.stream()
                    .filter(m -> {
                        Transaction tx = transactionsById.get(m.getTransactionId());
                        return tx != null && account.equals(tx.getAccount());
                    })
                    .toList();
        }
        return ReportDataAssembler.reconciliationExceptions(account, from, to, exceptions, transactionsById);
    }

    private List<Transaction> loadTransactions(String account, LocalDate from, LocalDate to) {
        return account == null || account.isBlank()
                ? transactionRepository.findByPostedDateBetween(from, to)
                : transactionRepository.findByAccountAndPostedDateBetween(account, from, to);
    }

    private List<FraudAlert> loadAlerts(String account, LocalDate from, LocalDate to) {
        return account == null || account.isBlank()
                ? fraudAlertRepository.findByDetectedAtBetween(startOfDay(from), endOfDay(to))
                : fraudAlertRepository.findByAccountAndDetectedAtBetween(account, startOfDay(from), endOfDay(to));
    }

    /** Defaults to the previous whole calendar month - the period the cron job reports on. */
    private LocalDate[] resolvePeriod(LocalDate from, LocalDate to) {
        if (from != null && to != null) {
            return new LocalDate[]{from, to};
        }
        YearMonth previousMonth = YearMonth.from(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)).minusMonths(1);
        return new LocalDate[]{
                from != null ? from : previousMonth.atDay(1),
                to != null ? to : previousMonth.atEndOfMonth()
        };
    }

    private String fileName(ReportType type, String account, LocalDate from, LocalDate to, ReportFormat format) {
        String scope = account == null || account.isBlank() ? "all-accounts" : slug(account);
        return String.format("%s_%s_%s_%s.%s",
                type.name().toLowerCase().replace('_', '-'), scope, from, to, format.extension());
    }

    /**
     * Keeps a user-supplied account id from producing a path-traversing or
     * header-breaking filename. Dots are dropped entirely rather than merely
     * escaped: stripping only the separators still leaves ".." sitting in the
     * Content-Disposition filename, which is needless to expose.
     */
    private String slug(String value) {
        String cleaned = value.replaceAll("[^A-Za-z0-9_-]", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+|-+$", "");
        if (cleaned.isEmpty()) {
            return "account";
        }
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }

    private static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant endOfDay(LocalDate date) {
        return date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static String truncate(String value, int max) {
        return value != null && value.length() > max ? value.substring(0, max) : value;
    }
}
