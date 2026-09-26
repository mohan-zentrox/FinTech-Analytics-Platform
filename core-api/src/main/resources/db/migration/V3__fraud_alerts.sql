-- FRD S5.4 "Anomaly & Fraud Detection" - persisted findings of a fraud scan.
--
-- One row per (transaction, rule) so a re-scan of the same window updates the
-- existing finding instead of piling up duplicates; scan_id groups everything
-- produced by a single scan run (mirroring reconciliation_match.run_id).

CREATE TABLE fraud_alert (
    id              UUID PRIMARY KEY,
    scan_id         UUID          NOT NULL,
    transaction_id  UUID          NOT NULL,
    account         VARCHAR(100)  NOT NULL,
    rule_id         VARCHAR(50)   NOT NULL,
    severity        VARCHAR(20)   NOT NULL,
    score           NUMERIC(10,4) NOT NULL,
    reason          VARCHAR(500),
    status          VARCHAR(20)   NOT NULL,
    detected_at     TIMESTAMPTZ   NOT NULL,
    resolved_at     TIMESTAMPTZ,
    resolved_by     VARCHAR(100),
    resolution_note VARCHAR(500),
    CONSTRAINT uk_fraud_alert_transaction_rule UNIQUE (transaction_id, rule_id),
    CONSTRAINT fk_fraud_alert_transaction FOREIGN KEY (transaction_id)
        REFERENCES transactions (id) ON DELETE CASCADE
);

CREATE INDEX idx_fraud_alert_account_status ON fraud_alert (account, status);
CREATE INDEX idx_fraud_alert_scan_id ON fraud_alert (scan_id);
CREATE INDEX idx_fraud_alert_detected_at ON fraud_alert (detected_at);
