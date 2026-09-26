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

-- NOTE: the read-only `ledger_readonly` role for analytics-service used to be
-- created here with a hard-coded password, which silently disagreed with the
-- ANALYTICS_DB_PASSWORD supplied by docker-compose/.env. Role provisioning now
-- lives in V2__analytics_readonly_role.sql, where the password comes from a
-- Flyway placeholder and insufficient privileges degrade to a NOTICE instead of
-- failing the whole migration (managed/free-tier Postgres often forbids
-- CREATE ROLE).
