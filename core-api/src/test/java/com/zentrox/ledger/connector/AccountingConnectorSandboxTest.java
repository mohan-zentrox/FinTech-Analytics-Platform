package com.zentrox.ledger.connector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Sandbox-mode behaviour shared by every connector (FRD S6.2).
 *
 * The property that matters most here is determinism: the sandbox exists so the
 * connector flow can be demonstrated without provider credentials, and that is
 * only useful if a second sync de-duplicates exactly as a real provider's would.
 */
class AccountingConnectorSandboxTest {

    private ConnectorProperties properties;
    private OAuthTokenClient tokenClient;

    /** Minimal concrete connector; the live path must never be reached in sandbox mode. */
    private static class TestConnector extends AbstractAccountingConnector {
        private int liveCalls = 0;

        TestConnector(ConnectorProperties properties, OAuthTokenClient tokenClient) {
            super(properties, tokenClient);
        }

        @Override
        public String providerName() {
            return "quickbooks";
        }

        @Override
        protected SyncPage fetchLiveTransactions(String accessToken, String realmId, String account,
                                                 LocalDate since, String cursor) {
            liveCalls++;
            return SyncPage.last(List.of());
        }
    }

    @BeforeEach
    void setUp() {
        properties = new ConnectorProperties();
        tokenClient = mock(OAuthTokenClient.class);
    }

    private TestConnector sandboxConnector() {
        properties.setSandbox(true);
        return new TestConnector(properties, tokenClient);
    }

    private List<ExternalTransaction> drain(AccountingConnector connector, String account, LocalDate since) {
        List<ExternalTransaction> all = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        do {
            SyncPage page = connector.fetchTransactions("token", "realm", account, since, cursor);
            all.addAll(page.transactions());
            cursor = page.nextCursor();
        } while (cursor != null && ++guard < 10);
        return all;
    }

    @Test
    void sandboxModeIsOnWhenTheProviderHasNoCredentialsEvenWithTheGlobalFlagOff() {
        properties.setSandbox(false);
        // No client id/secret configured for quickbooks.
        TestConnector connector = new TestConnector(properties, tokenClient);

        assertThat(connector.isSandbox()).isTrue();
    }

    @Test
    void realCredentialsTurnSandboxModeOffWhenTheGlobalFlagIsOff() {
        properties.setSandbox(false);
        ConnectorProperties.Provider provider = new ConnectorProperties.Provider();
        provider.setClientId("client-abc");
        provider.setClientSecret("secret-xyz");
        provider.setTokenUri("https://provider.example/token");
        provider.setAuthorizationUri("https://provider.example/authorize");
        provider.setRedirectUri("https://app.example/callback");
        provider.setScope("accounting.read");
        properties.getProviders().put("quickbooks", provider);

        TestConnector connector = new TestConnector(properties, tokenClient);

        assertThat(connector.isSandbox()).isFalse();
    }

    @Test
    void globalSandboxFlagWinsEvenWhenCredentialsArePresent() {
        properties.setSandbox(true);
        ConnectorProperties.Provider provider = new ConnectorProperties.Provider();
        provider.setClientId("client-abc");
        provider.setClientSecret("secret-xyz");
        properties.getProviders().put("quickbooks", provider);

        assertThat(new TestConnector(properties, tokenClient).isSandbox()).isTrue();
    }

    @Test
    void sandboxExchangeNeverCallsTheRealTokenEndpoint() {
        TestConnector connector = sandboxConnector();

        OAuthTokenResponse token = connector.exchangeCode("any-code");

        assertThat(token.accessToken()).isEqualTo("sandbox-access-token-quickbooks");
        assertThat(token.refreshToken()).isEqualTo("sandbox-refresh-token-quickbooks");
        assertThat(token.expiresIn()).isEqualTo(3600L);
        verifyNoInteractions(tokenClient);
    }

    @Test
    void sandboxRefreshNeverCallsTheRealTokenEndpoint() {
        TestConnector connector = sandboxConnector();

        assertThat(connector.refreshAccessToken("whatever").accessToken())
                .isEqualTo("sandbox-access-token-quickbooks");
        verifyNoInteractions(tokenClient);
    }

    @Test
    void sandboxFetchNeverReachesTheLiveCodePath() {
        TestConnector connector = sandboxConnector();

        drain(connector, "ACC-1", LocalDate.of(2026, 6, 1));

        assertThat(connector.liveCalls).isZero();
    }

    @Test
    void sandboxFeedIsPagedAndTerminates() {
        TestConnector connector = sandboxConnector();

        SyncPage first = connector.fetchTransactions("t", "r", "ACC-1", LocalDate.of(2026, 6, 1), null);
        assertThat(first.transactions()).hasSize(5);
        assertThat(first.nextCursor()).isEqualTo("1");

        SyncPage second = connector.fetchTransactions("t", "r", "ACC-1", LocalDate.of(2026, 6, 1), "1");
        assertThat(second.transactions()).hasSize(5);
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void sandboxFeedIsDeterministicSoASecondSyncDeduplicatesCompletely() {
        TestConnector connector = sandboxConnector();
        LocalDate since = LocalDate.of(2026, 6, 1);

        List<ExternalTransaction> first = drain(connector, "ACC-1", since);
        List<ExternalTransaction> second = drain(connector, "ACC-1", since);

        assertThat(second).isEqualTo(first);
        assertThat(first).hasSize(10);
    }

    @Test
    void sandboxExternalIdsAreUniqueWithinARunAndScopedByAccount() {
        TestConnector connector = sandboxConnector();
        LocalDate since = LocalDate.of(2026, 6, 1);

        List<ExternalTransaction> accountOne = drain(connector, "ACC-1", since);
        List<ExternalTransaction> accountTwo = drain(connector, "ACC-2", since);

        Set<String> idsOne = accountOne.stream().map(ExternalTransaction::externalId).collect(Collectors.toSet());
        assertThat(idsOne).hasSize(accountOne.size());
        assertThat(idsOne).allMatch(id -> id.startsWith("quickbooks-ACC-1-"));

        Set<String> idsTwo = accountTwo.stream().map(ExternalTransaction::externalId).collect(Collectors.toSet());
        assertThat(idsTwo).doesNotContainAnyElementsOf(idsOne);
    }

    @Test
    void sandboxProducesBothInflowsAndOutflowsWithSaneFields() {
        TestConnector connector = sandboxConnector();

        List<ExternalTransaction> transactions = drain(connector, "ACC-1", LocalDate.of(2026, 6, 1));

        assertThat(transactions).anyMatch(t -> t.amount().signum() > 0);
        assertThat(transactions).anyMatch(t -> t.amount().signum() < 0);
        assertThat(transactions).allSatisfy(t -> {
            assertThat(t.account()).isEqualTo("ACC-1");
            assertThat(t.currency()).isEqualTo("USD");
            assertThat(t.postedDate()).isAfterOrEqualTo(LocalDate.of(2026, 6, 1));
            assertThat(t.description()).startsWith("[sandbox]");
            assertThat(t.amount().scale()).isEqualTo(2);
        });
    }

    @Test
    void sandboxToleratesACursorItDidNotIssue() {
        TestConnector connector = sandboxConnector();

        SyncPage page = connector.fetchTransactions("t", "r", "ACC-1", LocalDate.of(2026, 6, 1), "not-a-number");

        assertThat(page.transactions()).hasSize(5);
    }

    @Test
    void sandboxAuthorizationUrlRoundTripsThroughTheConfiguredRedirectUri() {
        properties.setSandbox(true);
        ConnectorProperties.Provider provider = new ConnectorProperties.Provider();
        provider.setRedirectUri("http://localhost:5173/connectors/callback");
        properties.getProviders().put("quickbooks", provider);
        TestConnector connector = new TestConnector(properties, tokenClient);

        String url = connector.buildAuthorizationUrl("state-123");

        assertThat(url).startsWith("http://localhost:5173/connectors/callback");
        assertThat(url).contains("provider=quickbooks").contains("state=state-123").contains("sandbox=true");
        assertThat(url).contains("code=sandbox-code-state-123");
    }

    @Test
    void liveAuthorizationUrlCarriesEveryRequiredOAuthParameter() {
        properties.setSandbox(false);
        ConnectorProperties.Provider provider = new ConnectorProperties.Provider();
        provider.setClientId("client-abc");
        provider.setClientSecret("secret-xyz");
        provider.setAuthorizationUri("https://appcenter.intuit.com/connect/oauth2");
        provider.setRedirectUri("https://app.example/connectors/callback");
        // Xero's multi-scope form: the space must survive as an encoded space.
        provider.setScope("offline_access accounting.transactions.read");
        properties.getProviders().put("quickbooks", provider);
        TestConnector connector = new TestConnector(properties, tokenClient);

        String url = connector.buildAuthorizationUrl("state-xyz");

        assertThat(url).startsWith("https://appcenter.intuit.com/connect/oauth2?");
        assertThat(url).contains("client_id=client-abc");
        assertThat(url).contains("response_type=code");
        assertThat(url).contains("state=state-xyz");
        // The redirect URI's separators must be percent-encoded, not left raw, or a
        // redirect_uri with its own query string would truncate the request.
        assertThat(url).contains("redirect_uri=https%3A%2F%2Fapp.example%2Fconnectors%2Fcallback");
        assertThat(url).contains("scope=offline_access%20accounting.transactions.read");
        assertThat(url).doesNotContain(" ").doesNotContain("+");
    }

    @Test
    void liveAuthorizationUrlEncodesARedirectUriThatCarriesItsOwnQueryString() {
        properties.setSandbox(false);
        ConnectorProperties.Provider provider = new ConnectorProperties.Provider();
        provider.setClientId("client-abc");
        provider.setClientSecret("secret-xyz");
        provider.setAuthorizationUri("https://provider.example/authorize");
        provider.setRedirectUri("https://app.example/callback?tenant=acme&env=prod");
        provider.setScope("read");
        properties.getProviders().put("quickbooks", provider);
        TestConnector connector = new TestConnector(properties, tokenClient);

        String url = connector.buildAuthorizationUrl("s1");

        // The '&' and '=' inside the redirect URI must not read as request separators.
        assertThat(url).contains("redirect_uri=https%3A%2F%2Fapp.example%2Fcallback%3Ftenant%3Dacme%26env%3Dprod");
        assertThat(url.split("&")).hasSize(5);
    }

    @Test
    void liveAuthorizationUrlAppendsToAnAuthorizationUriThatAlreadyHasAQuery() {
        properties.setSandbox(false);
        ConnectorProperties.Provider provider = new ConnectorProperties.Provider();
        provider.setClientId("client-abc");
        provider.setClientSecret("secret-xyz");
        provider.setAuthorizationUri("https://system.netsuite.com/authorize.nl?c=123456");
        provider.setRedirectUri("https://app.example/callback");
        provider.setScope("rest_webservices");
        properties.getProviders().put("quickbooks", provider);
        TestConnector connector = new TestConnector(properties, tokenClient);

        String url = connector.buildAuthorizationUrl("s1");

        assertThat(url).startsWith("https://system.netsuite.com/authorize.nl?c=123456&client_id=");
        assertThat(url).doesNotContain("?c=123456?");
    }

    @Test
    void liveAuthorizationUrlFailsLoudlyWithNoAuthorizationUriConfigured() {
        properties.setSandbox(false);
        ConnectorProperties.Provider provider = new ConnectorProperties.Provider();
        provider.setClientId("client-abc");
        provider.setClientSecret("secret-xyz");
        properties.getProviders().put("quickbooks", provider);
        TestConnector connector = new TestConnector(properties, tokenClient);

        assertThatThrownBy(() -> connector.buildAuthorizationUrl("s"))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("No authorization-uri configured");
    }
}
