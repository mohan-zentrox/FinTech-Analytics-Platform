package com.zentrox.ledger.connector;

import com.zentrox.ledger.dto.connector.ConnectRequest;
import com.zentrox.ledger.dto.connector.ConnectorStatusDto;
import com.zentrox.ledger.dto.connector.SyncResult;
import com.zentrox.ledger.entity.ConnectorCredential;
import com.zentrox.ledger.entity.ConnectorStatus;
import com.zentrox.ledger.entity.Transaction;
import com.zentrox.ledger.entity.TransactionStatus;
import com.zentrox.ledger.exception.ResourceNotFoundException;
import com.zentrox.ledger.repository.ConnectorCredentialRepository;
import com.zentrox.ledger.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Covers the provider-agnostic half of FRD S6.2: credential storage/encryption,
 * token refresh, paging, and (source, externalId) de-duplication. Uses a
 * hand-written fake connector rather than a mock so the paging loop is exercised
 * against a realistic multi-page feed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConnectorSyncServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T10:00:00Z");
    private static final String ACCOUNT = "ACC-1";

    @Mock
    private ConnectorCredentialRepository credentialRepository;
    @Mock
    private TransactionRepository transactionRepository;

    private ConnectorProperties properties;
    private TokenCipher tokenCipher;
    private FakeConnector connector;
    private ConnectorSyncService service;

    /** Configurable stand-in for a provider; records what it was asked for. */
    private static class FakeConnector implements AccountingConnector {
        private final List<SyncPage> pages = new ArrayList<>();
        private final List<String> requestedCursors = new ArrayList<>();
        private LocalDate requestedSince;
        private String requestedAccessToken;
        private OAuthTokenResponse exchangeResponse = new OAuthTokenResponse(
                "access-1", "refresh-1", "Bearer", 3600L, 8_726_400L, "accounting.read");
        private OAuthTokenResponse refreshResponse = new OAuthTokenResponse(
                "access-2", "refresh-2", "Bearer", 3600L, 8_726_400L, "accounting.read");
        private RuntimeException refreshFailure;
        private boolean sandbox = true;

        @Override
        public String providerName() {
            return "quickbooks";
        }

        @Override
        public String buildAuthorizationUrl(String state) {
            return "https://provider.example/authorize?state=" + state;
        }

        @Override
        public OAuthTokenResponse exchangeCode(String authorizationCode) {
            return exchangeResponse;
        }

        @Override
        public OAuthTokenResponse refreshAccessToken(String refreshToken) {
            if (refreshFailure != null) {
                throw refreshFailure;
            }
            return refreshResponse;
        }

        @Override
        public SyncPage fetchTransactions(String accessToken, String realmId, String account,
                                          LocalDate since, String cursor) {
            requestedAccessToken = accessToken;
            requestedSince = since;
            requestedCursors.add(cursor);
            int index = cursor == null ? 0 : Integer.parseInt(cursor);
            return pages.get(index);
        }

        @Override
        public boolean isSandbox() {
            return sandbox;
        }
    }

    @BeforeEach
    void setUp() {
        properties = new ConnectorProperties();
        properties.setEncryptionKey("unit-test-connector-encryption-key-0123456789");
        tokenCipher = new TokenCipher(properties);
        connector = new FakeConnector();

        service = new ConnectorSyncService(
                List.of(connector), credentialRepository, transactionRepository,
                tokenCipher, properties, Clock.fixed(NOW, ZoneOffset.UTC));

        when(credentialRepository.save(any(ConnectorCredential.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.existsBySourceAndExternalId(anyString(), anyString())).thenReturn(false);
    }

    private ExternalTransaction external(String externalId, String amount, LocalDate date) {
        return new ExternalTransaction(externalId, ACCOUNT, new BigDecimal(amount), "usd", date,
                "Supplier payment", "opex");
    }

    private ConnectorCredential storedCredential() {
        return ConnectorCredential.builder()
                .id(UUID.randomUUID())
                .provider("quickbooks")
                .account(ACCOUNT)
                .realmId("realm-123")
                .accessToken(tokenCipher.encrypt("stored-access-token"))
                .refreshToken(tokenCipher.encrypt("stored-refresh-token"))
                .accessTokenExpiresAt(NOW.plusSeconds(3600))
                .status(ConnectorStatus.CONNECTED)
                .createdAt(NOW.minusSeconds(86_400))
                .updatedAt(NOW.minusSeconds(86_400))
                .build();
    }

    // ---------------------------------------------------------------- connect

    @Test
    void connectStoresTokensEncryptedNeverInPlaintext() {
        when(credentialRepository.findByProviderAndAccount("quickbooks", ACCOUNT)).thenReturn(Optional.empty());

        ConnectorStatusDto status = service.connect("quickbooks",
                new ConnectRequest(ACCOUNT, "auth-code-xyz", "realm-999"));

        ArgumentCaptor<ConnectorCredential> captor = ArgumentCaptor.forClass(ConnectorCredential.class);
        verify(credentialRepository).save(captor.capture());
        ConnectorCredential saved = captor.getValue();

        assertThat(saved.getAccessToken()).isNotEqualTo("access-1");
        assertThat(saved.getRefreshToken()).isNotEqualTo("refresh-1");
        assertThat(tokenCipher.decrypt(saved.getAccessToken())).isEqualTo("access-1");
        assertThat(tokenCipher.decrypt(saved.getRefreshToken())).isEqualTo("refresh-1");
        assertThat(saved.getAccessTokenExpiresAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(saved.getRealmId()).isEqualTo("realm-999");
        assertThat(saved.getStatus()).isEqualTo(ConnectorStatus.CONNECTED);

        assertThat(status.connected()).isTrue();
        assertThat(status.provider()).isEqualTo("quickbooks");
    }

    @Test
    void reconnectingReplacesTheExistingCredentialInPlaceRatherThanFailing() {
        ConnectorCredential existing = storedCredential();
        when(credentialRepository.findByProviderAndAccount("quickbooks", ACCOUNT))
                .thenReturn(Optional.of(existing));
        existing.setStatus(ConnectorStatus.EXPIRED);
        existing.setLastSyncError("Token refresh failed");

        service.connect("quickbooks", new ConnectRequest(ACCOUNT, "fresh-code", "realm-123"));

        assertThat(existing.getStatus()).isEqualTo(ConnectorStatus.CONNECTED);
        assertThat(existing.getLastSyncError()).isNull();
        assertThat(tokenCipher.decrypt(existing.getAccessToken())).isEqualTo("access-1");
        verify(credentialRepository, never()).delete(any());
    }

    @Test
    void connectSuppliesASandboxRealmIdWhenNoneIsGiven() {
        when(credentialRepository.findByProviderAndAccount("quickbooks", ACCOUNT)).thenReturn(Optional.empty());
        connector.sandbox = true;

        service.connect("quickbooks", new ConnectRequest(ACCOUNT, "code", null));

        ArgumentCaptor<ConnectorCredential> captor = ArgumentCaptor.forClass(ConnectorCredential.class);
        verify(credentialRepository).save(captor.capture());
        assertThat(captor.getValue().getRealmId()).isEqualTo("sandbox-realm-quickbooks");
    }

    @Test
    void unknownProviderIsANotFound() {
        assertThatThrownBy(() -> service.connector("sage"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Unknown connector provider: sage")
                .hasMessageContaining("quickbooks");
    }

    @Test
    void providerLookupIsCaseInsensitive() {
        assertThat(service.connector("QuickBooks").providerName()).isEqualTo("quickbooks");
    }

    // ------------------------------------------------------------------- sync

    @Test
    void syncImportsEveryPageAndStampsTheProviderAsTheTransactionSource() {
        when(credentialRepository.findByProviderAndAccount("quickbooks", ACCOUNT))
                .thenReturn(Optional.of(storedCredential()));
        connector.pages.add(new SyncPage(List.of(
                external("qb-1", "-100.00", LocalDate.of(2026, 7, 1)),
                external("qb-2", "250.00", LocalDate.of(2026, 7, 2))), "1"));
        connector.pages.add(SyncPage.last(List.of(
                external("qb-3", "-75.50", LocalDate.of(2026, 7, 3)))));

        SyncResult result = service.sync("quickbooks", ACCOUNT, LocalDate.of(2026, 7, 1));

        assertThat(result.pagesFetched()).isEqualTo(2);
        assertThat(result.fetched()).isEqualTo(3);
        assertThat(result.imported()).isEqualTo(3);
        assertThat(result.skipped()).isZero();
        assertThat(result.errored()).isZero();
        assertThat(connector.requestedCursors).containsExactly(null, "1");

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(3)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(tx -> {
            assertThat(tx.getSource()).isEqualTo("quickbooks");
            assertThat(tx.getAccount()).isEqualTo(ACCOUNT);
            assertThat(tx.getStatus()).isEqualTo(TransactionStatus.POSTED);
            // Currency is normalised even though the provider sent lowercase.
            assertThat(tx.getCurrency()).isEqualTo("USD");
        });
    }

    @Test
    void syncSkipsRowsAlreadyInTheLedgerSoARepeatSyncImportsNothing() {
        when(credentialRepository.findByProviderAndAccount("quickbooks", ACCOUNT))
                .thenReturn(Optional.of(storedCredential()));
        connector.pages.add(SyncPage.last(List.of(
                external("qb-1", "-100.00", LocalDate.of(2026, 7, 1)),
                external("qb-2", "250.00", LocalDate.of(2026, 7, 2)))));
        when(transactionRepository.existsBySourceAndExternalId("quickbooks", "qb-1")).thenReturn(true);
        when(transactionRepository.existsBySourceAndExternalId("quickbooks", "qb-2")).thenReturn(true);

        SyncResult result = service.sync("quickbooks", ACCOUNT, LocalDate.of(2026, 7, 1));

        assertThat(result.fetched()).isEqualTo(2);
        assertThat(result.imported()).isZero();
        assertThat(result.skipped()).isEqualTo(2);
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void syncDeduplicatesRepeatedExternalIdsWithinASingleRun() {
        when(credentialRepository.findByProviderAndAccount("quickbooks", ACCOUNT))
                .thenReturn(Optional.of(storedCredential()));
        connector.pages.add(new SyncPage(List.of(external("qb-dup", "-10.00", LocalDate.of(2026, 7, 1))), "1"));
        // The provider re-serves the same row on the next page (overlapping pagination).
        connector.pages.add(SyncPage.last(List.of(external("qb-dup", "-10.00", LocalDate.of(2026, 7, 1)))));

        SyncResult result = service.sync("quickbooks", ACCOUNT, LocalDate.of(2026, 7, 1));

        assertThat(result.fetched()).isEqualTo(2);
        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void syncStopsAtTheMaxPagesLimitAndSaysSoInsteadOfLoopingForever() {
        properties.setSyncMaxPages(2);
        when(credentialRepository.findByProviderAndAccount("quickbooks", ACCOUNT))
                .thenReturn(Optional.of(storedCredential()));
        // Every page claims another page follows.
        connector.pages.add(new SyncPage(List.of(external("qb-a", "-1.00", LocalDate.of(2026, 7, 1))), "1"));
        connector.pages.add(new SyncPage(List.of(external("qb-b", "-2.00", LocalDate.of(2026, 7, 2))), "2"));
        connector.pages.add(new SyncPage(List.of(external("qb-c", "-3.00", LocalDate.of(2026, 7, 3))), "3"));

        SyncResult result = service.sync("quickbooks", ACCOUNT, LocalDate.of(2026, 7, 1));

        assertThat(result.pagesFetched()).isEqualTo(2);
        assertThat(result.imported()).isEqualTo(2);
        assertThat(result.errors()).anySatisfy(e -> assertThat(e).contains("sync-max-pages"));
    }

    @Test
    void syncRecordsPerRowFailuresWithoutAbandoningTheRestOfThePage() {
        when(credentialRepository.findByProviderAndAccount("quickbooks", ACCOUNT))
                .thenReturn(Optional.of(storedCredential()));
        connector.pages.add(SyncPage.last(List.of(
                new ExternalTransaction("qb-bad", ACCOUNT, null, "USD",
                        LocalDate.of(2026, 7, 1), "no amount", null),
                external("qb-good", "-5.00", LocalDate.of(2026, 7, 2)))));

        SyncResult result = service.sync("quickbooks", ACCOUNT, LocalDate.of(2026, 7, 1));

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.errored()).isEqualTo(1);
        assertThat(result.errors().get(0)).contains("qb-bad");
    }

    @Test
    void syncDefaultsSinceToADayBeforeTheLastSuccessfulSync() {
        ConnectorCredential credential = storedCredential();
        credential.setLastSyncAt(Instant.parse("2026-07-20T08:00:00Z"));
        when(credentialRepository.findByProviderAndAccount("quickbooks", ACCOUNT))
                .thenReturn(Optional.of(credential));
        connector.pages.add(SyncPage.last(List.of()));

        SyncResult result = service.sync("quickbooks", ACCOUNT, null);

        assertThat(result.since()).isEqualTo(LocalDate.of(2026, 7, 19));
        assertThat(connector.requestedSince).isEqualTo(LocalDate.of(2026, 7, 19));
    }

    @Test
    void firstEverSyncReachesBackNinetyDays() {
        ConnectorCredential credential = storedCredential();
        credential.setLastSyncAt(null);
        when(credentialRepository.findByProviderAndAccount("quickbooks", ACCOUNT))
                .thenReturn(Optional.of(credential));
        connector.pages.add(SyncPage.last(List.of()));

        SyncResult result = service.sync("quickbooks", ACCOUNT, null);

        assertThat(result.since()).isEqualTo(LocalDate.of(2026, 8, 1).minusDays(90));
    }

    @Test
    void syncingWithoutAStoredConnectionIsANotFound() {
        when(credentialRepository.findByProviderAndAccount("quickbooks", ACCOUNT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sync("quickbooks", ACCOUNT, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("run the connect flow first");
    }

    @Test
    void syncingADisconnectedConnectionIsRejected() {
        ConnectorCredential credential = storedCredential();
        credential.setStatus(ConnectorStatus.DISCONNECTED);
        when(credentialRepository.findByProviderAndAccount("quickbooks", ACCOUNT))
                .thenReturn(Optional.of(credential));

        assertThatThrownBy(() -> service.sync("quickbooks", ACCOUNT, null))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("disconnected");
    }

    @Test
    void anUpstreamFailureIsRecordedOnTheCredentialBeforePropagating() {
        ConnectorCredential credential = storedCredential();
        when(credentialRepository.findByProviderAndAccount("quickbooks", ACCOUNT))
                .thenReturn(Optional.of(credential));
        // No pages configured -> fetchTransactions blows up on pages.get(0).

        assertThatThrownBy(() -> service.sync("quickbooks", ACCOUNT, LocalDate.of(2026, 7, 1)))
                .isInstanceOf(RuntimeException.class);

        assertThat(credential.getLastSyncError()).isNotBlank();
    }

    // ---------------------------------------------------------- token refresh

    @Test
    void usesTheStoredAccessTokenWhileItIsStillValid() {
        ConnectorCredential credential = storedCredential();
        when(credentialRepository.findByProviderAndAccount("quickbooks", ACCOUNT))
                .thenReturn(Optional.of(credential));
        connector.pages.add(SyncPage.last(List.of()));

        service.sync("quickbooks", ACCOUNT, LocalDate.of(2026, 7, 1));

        assertThat(connector.requestedAccessToken).isEqualTo("stored-access-token");
    }

    @Test
    void refreshesAnExpiredAccessTokenAndPersistsTheNewOne() {
        ConnectorCredential credential = storedCredential();
        credential.setAccessTokenExpiresAt(NOW.minusSeconds(10));
        when(credentialRepository.findByProviderAndAccount("quickbooks", ACCOUNT))
                .thenReturn(Optional.of(credential));
        connector.pages.add(SyncPage.last(List.of()));

        service.sync("quickbooks", ACCOUNT, LocalDate.of(2026, 7, 1));

        assertThat(connector.requestedAccessToken).isEqualTo("access-2");
        assertThat(tokenCipher.decrypt(credential.getAccessToken())).isEqualTo("access-2");
        assertThat(tokenCipher.decrypt(credential.getRefreshToken())).isEqualTo("refresh-2");
        assertThat(credential.getAccessTokenExpiresAt()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    void refreshesPreEmptivelyInsideTheExpirySkewWindow() {
        ConnectorCredential credential = storedCredential();
        // Valid for another 30s, which is inside the 120s skew.
        credential.setAccessTokenExpiresAt(NOW.plusSeconds(30));
        when(credentialRepository.findByProviderAndAccount("quickbooks", ACCOUNT))
                .thenReturn(Optional.of(credential));
        connector.pages.add(SyncPage.last(List.of()));

        service.sync("quickbooks", ACCOUNT, LocalDate.of(2026, 7, 1));

        assertThat(connector.requestedAccessToken).isEqualTo("access-2");
    }

    @Test
    void keepsTheExistingRefreshTokenWhenTheProviderDoesNotRotateIt() {
        ConnectorCredential credential = storedCredential();
        credential.setAccessTokenExpiresAt(NOW.minusSeconds(10));
        connector.refreshResponse = new OAuthTokenResponse("access-2", null, "Bearer", 3600L, null, null);
        when(credentialRepository.findByProviderAndAccount("quickbooks", ACCOUNT))
                .thenReturn(Optional.of(credential));
        connector.pages.add(SyncPage.last(List.of()));

        service.sync("quickbooks", ACCOUNT, LocalDate.of(2026, 7, 1));

        assertThat(tokenCipher.decrypt(credential.getRefreshToken())).isEqualTo("stored-refresh-token");
    }

    @Test
    void marksTheConnectionExpiredWhenRefreshFails() {
        ConnectorCredential credential = storedCredential();
        credential.setAccessTokenExpiresAt(NOW.minusSeconds(10));
        connector.refreshFailure = new ConnectorException("invalid_grant");
        when(credentialRepository.findByProviderAndAccount("quickbooks", ACCOUNT))
                .thenReturn(Optional.of(credential));

        assertThatThrownBy(() -> service.sync("quickbooks", ACCOUNT, LocalDate.of(2026, 7, 1)))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("re-authorise");

        assertThat(credential.getStatus()).isEqualTo(ConnectorStatus.EXPIRED);
        assertThat(credential.getLastSyncError()).contains("invalid_grant");
    }

    @Test
    void marksTheConnectionExpiredWhenThereIsNoRefreshTokenToUse() {
        ConnectorCredential credential = storedCredential();
        credential.setAccessTokenExpiresAt(NOW.minusSeconds(10));
        credential.setRefreshToken(null);
        when(credentialRepository.findByProviderAndAccount("quickbooks", ACCOUNT))
                .thenReturn(Optional.of(credential));

        assertThatThrownBy(() -> service.sync("quickbooks", ACCOUNT, LocalDate.of(2026, 7, 1)))
                .isInstanceOf(ConnectorException.class)
                .hasMessageContaining("no refresh token is stored");

        assertThat(credential.getStatus()).isEqualTo(ConnectorStatus.EXPIRED);
    }

    // --------------------------------------------------------- status listing

    @Test
    void listsProvidersWithNoConnectionAsNotConnectedRatherThanOmittingThem() {
        when(credentialRepository.findByProvider("quickbooks")).thenReturn(List.of());

        List<ConnectorStatusDto> statuses = service.listStatuses();

        assertThat(statuses).hasSize(1);
        assertThat(statuses.get(0).provider()).isEqualTo("quickbooks");
        assertThat(statuses.get(0).connected()).isFalse();
        assertThat(statuses.get(0).status()).isEqualTo(ConnectorStatus.DISCONNECTED);
    }

    @Test
    void statusNeverExposesTokenMaterial() {
        when(credentialRepository.findByProvider("quickbooks")).thenReturn(List.of(storedCredential()));

        List<ConnectorStatusDto> statuses = service.listStatuses();

        String rendered = statuses.toString();
        assertThat(rendered).doesNotContain("stored-access-token").doesNotContain("stored-refresh-token");
        // And the ciphertext is not leaked either.
        Set<String> fields = new HashSet<>(List.of(rendered.split("[,{}=]")));
        assertThat(fields).noneSatisfy(f -> assertThat(f).contains("accessToken="));
    }

    @Test
    void disconnectDeletesTheStoredCredential() {
        ConnectorCredential credential = storedCredential();
        when(credentialRepository.findByProviderAndAccount("quickbooks", ACCOUNT))
                .thenReturn(Optional.of(credential));

        service.disconnect("quickbooks", ACCOUNT);

        verify(credentialRepository).delete(credential);
    }

    @Test
    void disconnectingSomethingNotConnectedIsANotFound() {
        when(credentialRepository.findByProviderAndAccount(eq("quickbooks"), anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.disconnect("quickbooks", "ACC-NOPE"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void exposesTheConfiguredProviderList() {
        assertThat(service.providerNames()).containsExactly("quickbooks");
    }
}
