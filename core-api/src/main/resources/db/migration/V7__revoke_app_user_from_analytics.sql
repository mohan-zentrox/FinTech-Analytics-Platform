-- Removes the analytics read-only role's access to the user table.
--
-- V2 granted, and V6 re-asserted, `SELECT ON ... app_user TO ledger_readonly`.
-- app_user holds `password_hash`, so the analytics service's credentials were
-- enough to read every user's bcrypt hash:
--
--     psql -U ledger_readonly -c 'SELECT username, password_hash FROM app_user'
--
-- analytics-service never needed it. It reads exactly one table - see
-- TRANSACTION_COLUMNS and the single SELECT in analytics-service/app/db.py,
-- which queries `transactions` and nothing else. The grant was collateral from
-- the same over-broad default-privileges rule V6 was written to undo; V6 simply
-- carried `app_user` forward in its re-assert list without re-examining it.
--
-- V2 and V6 are left untouched so their checksums stay valid on databases that
-- have already applied them. This migration converges fresh and existing
-- databases on the same intended grants.
--
-- audit_log and reconciliation_match are deliberately retained: they contain no
-- credential material and are legitimate analytics inputs.

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'ledger_readonly') THEN
        RAISE NOTICE 'Skipping app_user revocation: role ledger_readonly does not exist.';
        RETURN;
    END IF;

    EXECUTE 'REVOKE ALL ON app_user FROM ledger_readonly';

    -- Re-assert what analytics legitimately reads, so this file alone describes
    -- the end state (idempotent).
    EXECUTE 'GRANT SELECT ON transactions, audit_log, reconciliation_match TO ledger_readonly';
EXCEPTION
    WHEN insufficient_privilege THEN
        RAISE NOTICE 'Skipping app_user revocation: the migration user lacks the required privileges.';
    WHEN undefined_table THEN
        RAISE NOTICE 'Skipping app_user revocation: expected tables are absent.';
END
$$;
