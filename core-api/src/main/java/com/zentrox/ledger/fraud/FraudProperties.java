package com.zentrox.ledger.fraud;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Tunable thresholds for the FRD S5.4 anomaly rules, bound from
 * `ledger.fraud.*` in application.yml (every key also has an env-var
 * override - see .env.example). Owned by T2-DATA1 per BRD S4.2, which is
 * exactly why these are configuration and not constants in the rule engine.
 */
@Component
@ConfigurationProperties(prefix = "ledger.fraud")
@Getter
@Setter
public class FraudProperties {

    /** How far back a scan looks when no explicit window is given. */
    private int lookbackDays = 90;

    /** Two otherwise-identical payments this many days apart or less are a suspected duplicate. */
    private int duplicateWindowDays = 7;

    /** Minimum |z-score| of an amount within its (account, category) peer group to alert. */
    private BigDecimal outlierZThreshold = new BigDecimal("3.0");

    /** Peer groups smaller than this are skipped - too little history for a meaningful z-score. */
    private int outlierMinSampleSize = 8;

    /** Minimum |z-score| of a day's transaction count against that account's daily average. */
    private BigDecimal velocityZThreshold = new BigDecimal("3.0");

    /** A day with fewer transactions than this is never a velocity spike, however unusual. */
    private int velocityMinDailyCount = 5;

    /** Amounts that are an exact multiple of this value are "round". */
    private BigDecimal roundAmountMultiple = new BigDecimal("1000");

    /** Round amounts below this are ignored (a 1,000 invoice is not remarkable). */
    private BigDecimal roundAmountFloor = new BigDecimal("10000");

    /** When true, a scan also sets the offending transactions' status to FLAGGED. */
    private boolean autoFlagTransactions = false;
}
