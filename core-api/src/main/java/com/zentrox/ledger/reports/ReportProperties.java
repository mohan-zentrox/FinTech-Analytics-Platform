package com.zentrox.ledger.reports;

import com.zentrox.ledger.entity.ReportFormat;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Bound from `ledger.reports.*` (FRD S6.1). See .env.example for the env-var overrides. */
@Component
@ConfigurationProperties(prefix = "ledger.reports")
@Getter
@Setter
public class ReportProperties {

    /**
     * Whether the monthly cron job runs at all. Defaults to false because on
     * scale-to-zero hosting (Render free, Cloud Run) a sleeping container cannot
     * fire a timer, and a schedule that silently never runs is worse than no
     * schedule: drive generation from an external scheduler there instead.
     */
    private boolean scheduleEnabled = false;

    /** Spring cron expression - six fields, second-precision. */
    private String cron = "0 0 2 1 * *";

    private String zone = "UTC";

    private ReportFormat defaultFormat = ReportFormat.PDF;

    /** Stored reports older than this are pruned by the scheduled job; 0 keeps them forever. */
    private int retentionDays = 365;
}
