package com.zentrox.ledger.connector;

import org.springframework.stereotype.Service;

/**
 * SCAFFOLD ONLY - TODO (FRD S6.2): Xero OAuth2 connector.
 * Ref: https://developer.xero.com/documentation/
 */
@Service
public class XeroConnectorService implements AccountingConnector {

    @Override
    public String providerName() {
        return "xero";
    }
}
