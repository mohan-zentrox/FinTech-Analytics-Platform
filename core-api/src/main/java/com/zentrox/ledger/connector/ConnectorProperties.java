package com.zentrox.ledger.connector;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bound from `ledger.connectors.*` (FRD S6.2).
 *
 * Sandbox mode exists so the connector feature is demoable end-to-end without an
 * Intuit / Xero / NetSuite developer account: the OAuth handshake and the
 * transaction sync are simulated deterministically. A provider with real
 * credentials configured always goes live regardless of the global sandbox flag,
 * so a half-configured environment cannot accidentally send fake data to a real
 * tenant - or silently stop talking to a real one.
 */
@Component
@ConfigurationProperties(prefix = "ledger.connectors")
@Getter
@Setter
public class ConnectorProperties {

    /** AES-256-GCM key material for {@link TokenCipher}. Must be overridden outside local dev. */
    private String encryptionKey = "local-dev-only-connector-key-0123456789abcdef";

    /** Default mode for providers with no client credentials configured. */
    private boolean sandbox = true;

    /** Page size requested from the provider's transaction API. */
    private int syncPageSize = 100;

    /** Hard stop on pages per sync, so a provider paginating forever cannot hang the request. */
    private int syncMaxPages = 20;

    private Map<String, Provider> providers = new LinkedHashMap<>();

    public Provider forProvider(String name) {
        return providers.getOrDefault(name.toLowerCase(), new Provider());
    }

    /**
     * True when this provider should use the simulated flow: either the global
     * sandbox flag is on, or the provider has no client id/secret to talk to a
     * real API with.
     */
    public boolean isSandboxFor(String name) {
        return sandbox || !forProvider(name).hasCredentials();
    }

    @Getter
    @Setter
    public static class Provider {

        private String clientId;
        private String clientSecret;
        private String redirectUri;
        private String authorizationUri;
        private String tokenUri;
        private String apiBaseUri;
        private String scope;

        public boolean hasCredentials() {
            return clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank();
        }
    }
}
