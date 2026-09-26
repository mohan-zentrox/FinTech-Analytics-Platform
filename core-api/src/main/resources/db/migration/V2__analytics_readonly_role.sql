-- Read-only role for the analytics-service (Python/FastAPI), per FRD S5.5.
--
-- The password comes from the Flyway placeholder `analyticsDbPassword`, wired in
-- application.yml to the ANALYTICS_DB_PASSWORD environment variable. That keeps
-- this role's credentials in exactly one place: previously V1 hard-coded
-- 'ledger_readonly_password' while docker-compose passed a different default, so
-- analytics-service could not authenticate on a fresh stack.
--
-- Note: do NOT write an env-var reference in dollar-brace form anywhere in this
-- file, including in comments. Flyway scans the whole script for placeholders and
-- fails the migration - and therefore application startup - on any it cannot
-- resolve, comments included.
--
-- Everything is wrapped so that a Postgres deployment which forbids CREATE
-- ROLE / GRANT (common on managed free tiers) emits a NOTICE and continues
-- rather than failing the migration and preventing core-api from starting. In
-- that case point ANALYTICS_DB_USER/ANALYTICS_DB_PASSWORD at whatever
-- read-only credentials the provider gave you.

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'ledger_readonly') THEN
        EXECUTE format('CREATE ROLE ledger_readonly LOGIN PASSWORD %L', '${analyticsDbPassword}');
    ELSE
        EXECUTE format('ALTER ROLE ledger_readonly WITH LOGIN PASSWORD %L', '${analyticsDbPassword}');
    END IF;

    EXECUTE format('GRANT CONNECT ON DATABASE %I TO ledger_readonly', current_database());
    EXECUTE 'GRANT USAGE ON SCHEMA public TO ledger_readonly';
    EXECUTE 'GRANT SELECT ON transactions, audit_log, reconciliation_match, app_user TO ledger_readonly';
    EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO ledger_readonly';
EXCEPTION
    WHEN insufficient_privilege THEN
        RAISE NOTICE 'Skipping ledger_readonly role provisioning: the migration user lacks CREATE ROLE/GRANT privileges. Configure ANALYTICS_DB_USER/ANALYTICS_DB_PASSWORD with provider-issued read-only credentials instead.';
    WHEN feature_not_supported THEN
        RAISE NOTICE 'Skipping ledger_readonly role provisioning: not supported by this Postgres deployment.';
END
$$;
