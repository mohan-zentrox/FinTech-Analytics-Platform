package com.zentrox.ledger.connector;

import java.time.LocalDate;

/**
 * Contract every accounting-source connector implements (FRD S6.2), so the
 * ingestion pipeline can treat QuickBooks, Xero and NetSuite uniformly.
 *
 * Implementations own only the provider-specific parts - the authorization URL's
 * query shape and the mapping from the provider's JSON to
 * {@link ExternalTransaction}. Everything common (token exchange, refresh,
 * encrypted persistence, pagination, de-duplicated upsert into the ledger) lives
 * in {@link AbstractAccountingConnector} and {@link ConnectorSyncService}.
 *
 * The previous version of this interface defaulted every method to
 * {@code UnsupportedOperationException}; those defaults are gone, so a new
 * provider cannot silently compile into a no-op.
 */
public interface AccountingConnector {

    /** Lower-case provider key, matching the `ledger.connectors.providers.*` config key. */
    String providerName();

    /**
     * URL to send the administrator to in order to grant access.
     *
     * @param state CSRF/state value the provider echoes back to the redirect URI
     */
    String buildAuthorizationUrl(String state);

    /** Exchanges the authorization code the provider redirected back with. */
    OAuthTokenResponse exchangeCode(String authorizationCode);

    /** Trades a refresh token for a new access token. */
    OAuthTokenResponse refreshAccessToken(String refreshToken);

    /**
     * Fetches one page of transactions.
     *
     * @param cursor null for the first page, otherwise the previous page's
     *               {@link SyncPage#nextCursor()}
     */
    SyncPage fetchTransactions(String accessToken, String realmId, String account,
                               LocalDate since, String cursor);

    /** True when this connector is operating against the simulated provider. */
    boolean isSandbox();
}
