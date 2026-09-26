package com.zentrox.ledger.reports;

import com.zentrox.ledger.dto.reports.ReportGenerateRequest;
import com.zentrox.ledger.entity.ReportRun;
import com.zentrox.ledger.entity.ReportStatus;
import com.zentrox.ledger.entity.ReportType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

/**
 * FRD S6.1 - the monthly report trigger.
 *
 * Only registered when `ledger.reports.schedule-enabled` is true, so the default
 * deployment has no background timer at all. That is deliberate: on scale-to-zero
 * hosting a cron trigger inside a sleeping container never fires, and a schedule
 * that silently does nothing is worse than an explicit external scheduler hitting
 * POST /api/reports/generate.
 *
 * The job is defensive about partial failure: one account's report failing must
 * not stop the remaining accounts, and the retention sweep runs regardless.
 */
@Component
@ConditionalOnProperty(prefix = "ledger.reports", name = "schedule-enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class ReportScheduler {

    private final ReportGenerationService reportGenerationService;
    private final ReportProperties properties;
    private final Clock clock;

    @Scheduled(cron = "${ledger.reports.cron}", zone = "${ledger.reports.zone}")
    public void generateMonthlyReports() {
        YearMonth period = YearMonth.from(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)).minusMonths(1);
        LocalDate from = period.atDay(1);
        LocalDate to = period.atEndOfMonth();

        List<String> accounts = reportGenerationService.accountsWithActivity(from, to);
        log.info("Scheduled monthly reporting for {}: {} account(s) with activity", period, accounts.size());

        int succeeded = 0;
        int failed = 0;

        for (String account : accounts) {
            for (ReportType type : List.of(ReportType.CASH_FLOW, ReportType.RECONCILIATION_EXCEPTIONS)) {
                try {
                    ReportRun run = reportGenerationService.generate(
                            new ReportGenerateRequest(type, properties.getDefaultFormat(), account, from, to),
                            "scheduler");
                    if (run.getStatus() == ReportStatus.COMPLETED) {
                        succeeded++;
                    } else {
                        failed++;
                    }
                } catch (RuntimeException e) {
                    // Keep going: one bad account must not cost every other account its report.
                    failed++;
                    log.error("Scheduled {} report failed for account {} ({}..{})", type, account, from, to, e);
                }
            }
        }

        log.info("Scheduled monthly reporting for {} finished: {} generated, {} failed", period, succeeded, failed);

        try {
            reportGenerationService.pruneOlderThanRetention();
        } catch (RuntimeException e) {
            log.error("Report retention sweep failed", e);
        }
    }
}
