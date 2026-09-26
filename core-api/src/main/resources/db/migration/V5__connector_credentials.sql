-- FRD S6.2 "Accounting Source Connectors" - per-(provider, account) OAuth2
-- credential store.
--
-- access_token/refresh_token are stored encrypted (AES-256-GCM, key from
-- CONNECTOR_ENCRYPTION_KEY) by TokenCipher, so a database dump alone does not
-- yield usable third-party credentials. Columns are TEXT because the
-- ciphertext is base64 and providers issue long opaque tokens.

CREATE TABLE connector_credential (
    id                       UUID PRIMARY KEY,
    provider                 VARCHAR(50)  NOT NULL,
    account                  VARCHAR(100) NOT NULL,
    realm_id                 VARCHAR(100),
    access_token             TEXT         NOT NULL,
    refresh_token            TEXT,
    access_token_expires_at  TIMESTAMPTZ,
    refresh_token_expires_at TIMESTAMPTZ,
    scope                    VARCHAR(500),
    status                   VARCHAR(20)  NOT NULL,
    last_sync_at             TIMESTAMPTZ,
    last_sync_cursor         VARCHAR(200),
    last_sync_error          VARCHAR(1000),
    created_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_connector_credential_provider_account UNIQUE (provider, account)
);

CREATE INDEX idx_connector_credential_provider ON connector_credential (provider);
