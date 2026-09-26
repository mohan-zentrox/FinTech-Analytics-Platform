-- Least-privilege correction for the analytics read-only role.
--
-- V2 ended with:
--     ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO ledger_readonly;
-- which silently granted SELECT on every table created *after* it - so
-- fraud_alert (V3), report_run (V4) and connector_credential (V5) all became
-- readable by the analytics service. That is more than it needs: analytics
-- computes its own anomaly scores from `transactions` and never reads persisted
-- alerts, generated report documents, or - most importantly - the OAuth token
-- ciphertext in connector_credential.
--
-- V2 is left untouched rather than edited, so its checksum stays valid for any
-- database that has already applied it; this migration converges both fresh and
-- existing databases on the same intended grants.
--
-- The four tables analytics is allowed to read are enumerated explicitly here, and
-- the default-privileges rule is withdrawn so a future table is NOT readable until
-- someone grants it deliberately.

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'ledger_readonly') THEN
        RAISE NOTICE 'Skipping analytics read-only scope restriction: role ledger_readonly does not exist.';
        RETURN;
    END IF;

    -- Stop auto-granting SELECT on tables created from here on.
    EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE SELECT ON TABLES FROM ledger_readonly';

    -- Withdraw the access the default-privileges rule handed out.
    EXECUTE 'REVOKE ALL ON fraud_alert FROM ledger_readonly';
    EXECUTE 'REVOKE ALL ON report_run FROM ledger_readonly';
    EXECUTE 'REVOKE ALL ON connector_credential FROM ledger_readonly';
    EXECUTE 'REVOKE ALL ON flyway_schema_history FROM ledger_readonly';

    -- Re-assert the intended grants (idempotent).
    EXECUTE 'GRANT SELECT ON transactions, audit_log, reconciliation_match, app_user TO ledger_readonly';
EXCEPTION
    WHEN insufficient_privilege THEN
        RAISE NOTICE 'Skipping analytics read-only scope restriction: the migration user lacks the required privileges.';
    WHEN undefined_table THEN
        RAISE NOTICE 'Skipping analytics read-only scope restriction: expected tables are absent.';
END
$$;
