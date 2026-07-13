package com.zentrox.ledger.connector;

/**
 * SCAFFOLD ONLY (FRD S6.2 "Accounting Source Connectors").
 *
 * Common contract that QuickBooks/Xero/NetSuite-style OAuth connectors will
 * implement so TransactionService/CsvImportService-equivalent ingestion
 * code can treat every accounting source uniformly.
 *
 * TODO: define the OAuth2 authorization-code flow (per-provider client
 * id/secret/redirect URI, token storage + refresh) - likely a new
 * `connector_credential` table keyed by (tenantId/accountId, provider).
 * TODO: define syncTransactions(accountId) to page through the provider's
 * transaction API and funnel results through the same canonical Transaction
 * schema + (source, externalId) de-dup used by CsvImportService.
 */
public interface AccountingConnector {

    String providerName();

    default void connect(String accountId, String authorizationCode) {
        throw new UnsupportedOperationException(
                providerName() + " OAuth connect() is not implemented - see FRD S6.2");
    }

    default void syncTransactions(String accountId) {
        throw new UnsupportedOperationException(
                providerName() + " syncTransactions() is not implemented - see FRD S6.2");
    }
}
