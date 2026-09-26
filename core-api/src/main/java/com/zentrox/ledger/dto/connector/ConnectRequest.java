package com.zentrox.ledger.dto.connector;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/connectors/{provider}/connect - completes the OAuth2
 * authorization-code exchange for one ledger account.
 *
 * @param realmId provider tenant id (QuickBooks realmId / Xero tenantId). Required
 *                for a live connection to those two; the sandbox supplies its own.
 */
public record ConnectRequest(
        @NotBlank @Size(max = 100) String account,
        @NotBlank String code,
        @Size(max = 100) String realmId
) {
}
