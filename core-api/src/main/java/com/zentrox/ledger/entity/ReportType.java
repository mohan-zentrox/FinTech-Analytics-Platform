package com.zentrox.ledger.entity;

/** Report templates available from {@code POST /api/reports/generate} (FRD S6.1). */
public enum ReportType {

    /** Monthly inflow / outflow / net per calendar month for one account. */
    CASH_FLOW,

    /** Unmatched transactions from reconciliation runs in the period. */
    RECONCILIATION_EXCEPTIONS,

    /** Open and recently-triaged anomaly alerts (FRD S5.4 output). */
    FRAUD_ALERTS
}
