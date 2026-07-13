-- Project Ledger - initial schema
-- IDs are UUIDs generated application-side by Hibernate (org.hibernate.id.uuid.UuidGenerator),
-- so no DB-side default/extension is required.

CREATE TABLE app_user (
    id             UUID PRIMARY KEY,
    username       VARCHAR(100) NOT NULL,
    email          VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    role           VARCHAR(20)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_app_user_username UNIQUE (username),
    CONSTRAINT uk_app_user_email UNIQUE (email)
);

CREATE TABLE transactions (
    id            UUID PRIMARY KEY,
    source        VARCHAR(50)   NOT NULL,
    account       VARCHAR(100)  NOT NULL,
    amount        NUMERIC(19,4) NOT NULL,
    currency      VARCHAR(3)    NOT NULL,
    posted_date   DATE          NOT NULL,
    description   VARCHAR(500),
    category      VARCHAR(100),
    status        VARCHAR(20)   NOT NULL,
    external_id   VARCHAR(150)  NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL,
    updated_at    TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uk_transactions_source_external_id UNIQUE (source, external_id)
);

CREATE INDEX idx_transactions_account ON transactions (account);
CREATE INDEX idx_transactions_posted_date ON transactions (posted_date);
CREATE INDEX idx_transactions_status ON transactions (status);

CREATE TABLE audit_log (
    id          UUID PRIMARY KEY,
    actor       VARCHAR(100) NOT NULL,
    action      VARCHAR(50)  NOT NULL,
    entity      VARCHAR(100) NOT NULL,
    entity_id   VARCHAR(100),
    details     VARCHAR(1000),
    "timestamp" TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_audit_log_entity ON audit_log (entity, entity_id);
CREATE INDEX idx_audit_log_timestamp ON audit_log ("timestamp");

CREATE TABLE reconciliation_match (
    id                     UUID PRIMARY KEY,
    run_id                 UUID          NOT NULL,
    transaction_id         UUID          NOT NULL,
    matched_transaction_id UUID,
    match_type             VARCHAR(20)   NOT NULL,
    amount_delta           NUMERIC(19,4),
    date_delta_days        INTEGER,
    created_at             TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_recon_run_id ON reconciliation_match (run_id);

-- Read-only role for the analytics-service (Python/FastAPI), per FRD S5.5.
-- Password must be rotated and supplied via ANALYTICS_DB_PASSWORD in every
-- non-local environment; this default is for docker-compose local dev only.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'ledger_readonly') THEN
        CREATE ROLE ledger_readonly LOGIN PASSWORD 'ledger_readonly_password';
    END IF;
END
$$;

DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO ledger_readonly', current_database());
END
$$;

GRANT USAGE ON SCHEMA public TO ledger_readonly;
GRANT SELECT ON transactions, audit_log, reconciliation_match, app_user TO ledger_readonly;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO ledger_readonly;
