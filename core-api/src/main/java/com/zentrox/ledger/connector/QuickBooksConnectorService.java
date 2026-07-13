package com.zentrox.ledger.connector;

import org.springframework.stereotype.Service;

/**
 * SCAFFOLD ONLY - TODO (FRD S6.2): Intuit QuickBooks Online OAuth2 connector.
 * Ref: https://developer.intuit.com/app/developer/qbo/docs/develop
 */
@Service
public class QuickBooksConnectorService implements AccountingConnector {

    @Override
    public String providerName() {
        return "quickbooks";
    }
}
