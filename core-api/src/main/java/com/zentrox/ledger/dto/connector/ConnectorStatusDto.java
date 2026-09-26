package com.zentrox.ledger.dto.connector;

import com.zentrox.ledger.entity.ConnectorCredential;
import com.zentrox.ledger.entity.ConnectorStatus;

import java.time.Instant;

/**
 * Connection state exposed to the frontend. Contains no token material of any
 * kind - not even a truncated prefix - so the connectors screen can never leak a
 * third-party credential into a browser, a log or a screenshot.
 */
public record ConnectorStatusDto(
        String provider,
        String account,
        boolean connected,
        boolean sandbox,
        ConnectorStatus status,
        String realmId,
        String scope,
        Instant accessTokenExpiresAt,
        Instant lastSyncAt,
        String lastSyncError
) {
    public static ConnectorStatusDto connected(ConnectorCredential credential, boolean sandbox) {
        return new ConnectorStatusDto(
                credential.getProvider(),
                credential.getAccount(),
                credential.getStatus() == ConnectorStatus.CONNECTED,
                sandbox,
                credential.getStatus(),
                credential.getRealmId(),
                credential.getScope(),
                credential.getAccessTokenExpiresAt(),
                credential.getLastSyncAt(),
                credential.getLastSyncError());
    }

    public static ConnectorStatusDto notConnected(String provider, boolean sandbox) {
        return new ConnectorStatusDto(provider, null, false, sandbox,
                ConnectorStatus.DISCONNECTED, null, null, null, null, null);
    }
}
