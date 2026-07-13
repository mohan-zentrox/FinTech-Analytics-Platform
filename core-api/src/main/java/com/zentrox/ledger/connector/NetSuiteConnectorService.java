package com.zentrox.ledger.connector;

import org.springframework.stereotype.Service;

/**
 * SCAFFOLD ONLY - TODO (FRD S6.2): Oracle NetSuite OAuth2/token-based connector.
 * Ref: https://docs.oracle.com/en/cloud/saas/netsuite/
 */
@Service
public class NetSuiteConnectorService implements AccountingConnector {

    @Override
    public String providerName() {
        return "netsuite";
    }
}
