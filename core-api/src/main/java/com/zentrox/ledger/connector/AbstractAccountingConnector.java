package com.zentrox.ledger.connector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shared OAuth2 behaviour for the three connectors (FRD S6.2).
 *
 * Subclasses supply the provider key and the live transaction fetch; this class
 * handles the standard authorization-URL construction, delegates token exchanges
 * to {@link OAuthTokenClient}, and provides the sandbox implementation.
 *
 * About sandbox mode: it is a deterministic generator, not random data. The same
 * (provider, account, since, cursor) always yields the same transactions and the
 * same external ids, which means a second sync imports zero rows - exactly the
 * idempotency the real providers require and the property most worth being able
 * to demonstrate without a developer account.
 */
@Slf4j
public abstract class AbstractAccountingConnector implements AccountingConnector {

    /** Transactions the sandbox generates per page. */
    private static final int SANDBOX_PAGE_SIZE = 5;

    /** Pages the sandbox serves before reporting the feed exhausted. */
    private static final int SANDBOX_TOTAL_PAGES = 2;

    protected final ConnectorProperties properties;
    protected final OAuthTokenClient tokenClient;

    protected AbstractAccountingConnector(ConnectorProperties properties, OAuthTokenClient tokenClient) {
        this.properties = properties;
        this.tokenClient = tokenClient;
    }

    protected ConnectorProperties.Provider config() {
        return properties.forProvider(providerName());
    }

    @Override
    public boolean isSandbox() {
        return properties.isSandboxFor(providerName());
    }

    @Override
    public String buildAuthorizationUrl(String state) {
        if (isSandbox()) {
            // A local URL the frontend can round-trip through, so the connect flow is
            // clickable end-to-end in sandbox mode.
            return UriComponentsBuilder.fromUriString(
                            config().getRedirectUri() == null
                                    ? "http://localhost:5173/connectors/callback"
                                    : config().getRedirectUri())
                    .queryParam("provider", providerName())
                    .queryParam("code", "sandbox-code-" + state)
                    .queryParam("state", state)
                    .queryParam("sandbox", "true")
                    .build()
                    .toUriString();
        }
        ConnectorProperties.Provider provider = config();
        if (provider.getAuthorizationUri() == null || provider.getAuthorizationUri().isBlank()) {
            throw new ConnectorException("No authorization-uri configured for provider " + providerName());
        }

        // Encoded explicitly rather than via UriComponentsBuilder's query encoding:
        // the latter leaves ':' and '/' as-is (they are legal in an RFC 3986 query),
        // but RFC 6749 requires authorization-request parameters to be
        // application/x-www-form-urlencoded. Without that, a redirect_uri or scope
        // containing '&' or '=' would silently truncate the request.
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", provider.getClientId());
        params.put("response_type", "code");
        params.put("redirect_uri", provider.getRedirectUri());
        params.put("scope", provider.getScope());
        params.put("state", state);

        String query = params.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(e -> e.getKey() + "=" + formEncode(e.getValue()))
                .collect(Collectors.joining("&"));

        String base = provider.getAuthorizationUri();
        return base + (base.contains("?") ? "&" : "?") + query;
    }

    /**
     * Form-encodes a value, then converts '+' back to %20. Both mean "space" to a
     * form parser, but %20 is also correct when the value is read straight out of
     * the URL's query component, which some providers do.
     */
    private static String formEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Override
    public OAuthTokenResponse exchangeCode(String authorizationCode) {
        if (isSandbox()) {
            log.info("Connector {} running in sandbox mode: simulating authorization-code exchange", providerName());
            return sandboxToken();
        }
        return tokenClient.exchangeAuthorizationCode(config(), authorizationCode);
    }

    @Override
    public OAuthTokenResponse refreshAccessToken(String refreshToken) {
        if (isSandbox()) {
            return sandboxToken();
        }
        return tokenClient.refresh(config(), refreshToken);
    }

    @Override
    public SyncPage fetchTransactions(String accessToken, String realmId, String account,
                                      LocalDate since, String cursor) {
        if (isSandbox()) {
            return sandboxPage(account, since, cursor);
        }
        return fetchLiveTransactions(accessToken, realmId, account, since, cursor);
    }

    /**
     * Provider-specific live fetch. Called only when not in sandbox mode, i.e.
     * only when real client credentials are configured.
     */
    protected abstract SyncPage fetchLiveTransactions(String accessToken, String realmId, String account,
                                                      LocalDate since, String cursor);

    // ------------------------------------------------------------- sandbox

    private OAuthTokenResponse sandboxToken() {
        return new OAuthTokenResponse(
                "sandbox-access-token-" + providerName(),
                "sandbox-refresh-token-" + providerName(),
                "Bearer",
                3600L,
                8_726_400L, // Intuit's 101-day refresh-token lifetime, for realism
                config().getScope());
    }

    /**
     * Deterministic synthetic feed. Amounts and dates are derived from the page
     * and row index, and each external id encodes (provider, account, page, row),
     * so re-syncing produces byte-identical rows and therefore zero new imports.
     */
    private SyncPage sandboxPage(String account, LocalDate since, String cursor) {
        int page = parsePage(cursor);
        LocalDate base = since == null ? LocalDate.now().minusDays(30) : since;
        List<ExternalTransaction> transactions = new ArrayList<>();

        for (int row = 0; row < SANDBOX_PAGE_SIZE; row++) {
            int index = page * SANDBOX_PAGE_SIZE + row;
            boolean inflow = index % 3 == 0;
            BigDecimal magnitude = BigDecimal.valueOf(125L + (long) index * 37L)
                    .setScale(2, RoundingMode.UNNECESSARY);

            transactions.add(new ExternalTransaction(
                    String.format("%s-%s-p%d-r%d", providerName(), account, page, row),
                    account,
                    inflow ? magnitude : magnitude.negate(),
                    "USD",
                    base.plusDays(index),
                    String.format("[sandbox] %s %s #%d",
                            providerName(), inflow ? "customer receipt" : "supplier payment", index),
                    inflow ? "revenue" : "opex"));
        }

        boolean hasMore = page + 1 < SANDBOX_TOTAL_PAGES;
        return new SyncPage(transactions, hasMore ? String.valueOf(page + 1) : null);
    }

    private int parsePage(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor.trim()));
        } catch (NumberFormatException e) {
            // A cursor this connector did not issue (e.g. left over from live mode).
            return 0;
        }
    }
}
