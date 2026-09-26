package com.zentrox.ledger.entity;

/**
 * Identifiers of the anomaly rules implemented by
 * {@link com.zentrox.ledger.fraud.FraudDetectionService} (FRD S5.4).
 * Persisted as a string in fraud_alert.rule_id, so adding a rule never
 * invalidates existing alerts.
 */
public enum FraudRule {

    /** Same account, same absolute amount and same normalised description inside the duplicate window. */
    DUPLICATE_PAYMENT,

    /** Amount is a statistical outlier within its (account, category) peer group. */
    AMOUNT_OUTLIER,

    /** Transaction count for a single day is far above that account's normal daily volume. */
    VELOCITY_SPIKE,

    /** Unusually round large amount - a common signature of manual/fabricated entries. */
    ROUND_AMOUNT
}
