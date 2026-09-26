package com.zentrox.ledger.connector;

import com.zentrox.ledger.aspect.Audited;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * FRD S6.2 "Accounting Source Connectors" - credential lifecycle and transaction
 * ingestion.
 *
 * The connect/refresh/sync orchestration lives here, provider-specific HTTP lives
 * in the {@link AccountingConnector} implementations, and tokens are encrypted by
 * {@link TokenCipher} on the way in and decrypted only at the moment of use.
 *
 * Ingestion funnels every provider's rows through the same canonical
 * {@link Transaction} shape and the same {@code (source, externalId)} uniqueness
 * that {@code CsvImportService} relies on, so a repeated sync is a no-op rather
 * than a duplicate-payment generator. The provider key is the {@code source}.
 */
@Service
@Slf4j
public class ConnectorSyncService {

    /** How far back a first-ever sync reaches when the credential has no high-water mark. */
    private static final int INITIAL_SYNC_LOOKBACK_DAYS = 90;

    /** Access tokens within this window of expiry are refreshed pre-emptively. */
    private static final long REFRESH_SKEW_SECONDS = 120;

    /** Cap on per-row error messages returned to the caller. */
    private static final int MAX_REPORTED_ERRORS = 20;

    private final Map<String, AccountingConnector> connectors = new LinkedHashMap<>();
    private final ConnectorCredentialRepository credentialRepository;
    private final TransactionRepository transactionRepository;
    private final TokenCipher tokenCipher;
    private final ConnectorProperties properties;
    private final Clock clock;

    public ConnectorSyncService(List<AccountingConnector> connectorBeans,
                                ConnectorCredentialRepository credentialRepository,
                                TransactionRepository transactionRepository,
                                TokenCipher tokenCipher,
                                ConnectorProperties properties,
                                Clock clock) {
        for (AccountingConnector connector : connectorBeans) {
            this.connectors.put(connector.providerName(), connector);
        }
        this.credentialRepository = credentialRepository;
        this.transactionRepository = transactionRepository;
        this.tokenCipher = tokenCipher;
        this.properties = properties;
        this.clock = clock;
    }

    public List<String> providerNames() {
        return List.copyOf(connectors.keySet());
    }

    public AccountingConnector connector(String provider) {
        AccountingConnector connector = connectors.get(provider == null ? "" : provider.toLowerCase());
        if (connector == null) {
            throw new ResourceNotFoundException("Unknown connector provider: " + provider
                    + " (available: " + String.join(", ", connectors.keySet()) + ")");
        }
        return connector;
    }

    /** Authorization URL to redirect an administrator to, for the consent step. */
    public String authorizationUrl(String provider, String state) {
        return connector(provider).buildAuthorizationUrl(state);
    }

    /**
     * Completes the authorization-code exchange and stores the encrypted tokens.
     * Re-connecting an existing (provider, account) pair replaces its credentials
     * in place, so re-authorising after a revocation does not need a manual delete.
     */
    @Audited(action = "CONNECT", entity = "ConnectorCredential")
    @Transactional
    public ConnectorStatusDto connect(String provider, ConnectRequest request) {
        AccountingConnector connector = connector(provider);
        OAuthTokenResponse token = connector.exchangeCode(request.code());

        ConnectorCredential credential = credentialRepository
                .findByProviderAndAccount(connector.providerName(), request.account())
                .orElseGet(() -> ConnectorCredential.builder()
                        .provider(connector.providerName())
                        .account(request.account())
                        .createdAt(clock.instant())
                        .build());

        applyToken(credential, token);
        credential.setRealmId(resolveRealmId(connector, request.realmId()));
        credential.setStatus(ConnectorStatus.CONNECTED);
        credential.setLastSyncError(null);
        credential.setUpdatedAt(clock.instant());

        ConnectorCredential saved = credentialRepository.save(credential);
        log.info("Connected {} for account {} (sandbox={})",
                connector.providerName(), request.account(), connector.isSandbox());
        return ConnectorStatusDto.connected(saved, connector.isSandbox());
    }

    /** Forgets the stored credentials for a (provider, account) pair. */
    @Audited(action = "DISCONNECT", entity = "ConnectorCredential")
    @Transactional
    public void disconnect(String provider, String account) {
        AccountingConnector connector = connector(provider);
        ConnectorCredential credential = credentialRepository
                .findByProviderAndAccount(connector.providerName(), account)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No " + connector.providerName() + " connection for account " + account));
        credentialRepository.delete(credential);
    }

    @Transactional(readOnly = true)
    public List<ConnectorStatusDto> listStatuses() {
        List<ConnectorStatusDto> statuses = new ArrayList<>();
        for (AccountingConnector connector : connectors.values()) {
            List<ConnectorCredential> credentials = credentialRepository.findByProvider(connector.providerName());
            if (credentials.isEmpty()) {
                statuses.add(ConnectorStatusDto.notConnected(connector.providerName(), connector.isSandbox()));
                continue;
            }
            for (ConnectorCredential credential : credentials) {
                statuses.add(ConnectorStatusDto.connected(credential, connector.isSandbox()));
            }
        }
        return statuses;
    }

    /**
     * Pulls the provider's transactions into the ledger.
     *
     * Paging stops at the provider's last page or {@code sync-max-pages},
     * whichever comes first - an upstream that paginates without end must not be
     * able to hang the request thread indefinitely.
     */
    @Audited(action = "SYNC", entity = "ConnectorCredential")
    @Transactional
    public SyncResult sync(String provider, String account, LocalDate since) {
        AccountingConnector connector = connector(provider);
        ConnectorCredential credential = credentialRepository
                .findByProviderAndAccount(connector.providerName(), account)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No " + connector.providerName() + " connection for account " + account
                                + " - run the connect flow first"));

        if (credential.getStatus() == ConnectorStatus.DISCONNECTED) {
            throw new ConnectorException("Connection for " + connector.providerName() + "/" + account
                    + " is disconnected; re-authorise before syncing");
        }

        String accessToken = ensureFreshAccessToken(connector, credential);
        LocalDate effectiveSince = resolveSince(since, credential);

        int pages = 0;
        int fetched = 0;
        int imported = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();
        Set<String> seenInRun = new HashSet<>();
        String cursor = null;

        try {
            do {
                SyncPage page = connector.fetchTransactions(
                        accessToken, credential.getRealmId(), account, effectiveSince, cursor);
                pages++;

                for (ExternalTransaction external : page.transactions()) {
                    fetched++;
                    try {
                        if (!seenInRun.add(external.externalId())
                                || transactionRepository.existsBySourceAndExternalId(
                                        connector.providerName(), external.externalId())) {
                            skipped++;
                            continue;
                        }
                        transactionRepository.save(toTransaction(connector.providerName(), external));
                        imported++;
                    } catch (RuntimeException rowError) {
                        // One malformed row must not abort the whole sync.
                        if (errors.size() < MAX_REPORTED_ERRORS) {
                            errors.add("externalId=" + external.externalId() + ": " + rowError.getMessage());
                        }
                    }
                }
                cursor = page.nextCursor();
            } while (cursor != null && pages < properties.getSyncMaxPages());

            if (cursor != null) {
                String message = "Stopped after " + pages + " page(s) at the sync-max-pages limit; "
                        + "more data remains upstream - re-run the sync to continue";
                log.warn("{} sync for account {}: {}", connector.providerName(), account, message);
                errors.add(message);
            }

            credential.setLastSyncAt(clock.instant());
            credential.setLastSyncCursor(cursor);
            credential.setLastSyncError(errors.isEmpty() ? null : truncate(String.join("; ", errors), 1000));
        } catch (RuntimeException e) {
            credential.setLastSyncError(truncate(e.getMessage() == null ? e.toString() : e.getMessage(), 1000));
            credentialRepository.save(credential);
            throw e;
        }

        credentialRepository.save(credential);

        log.info("{} sync for account {} ({} page(s)): {} fetched, {} imported, {} skipped, {} errored",
                connector.providerName(), account, pages, fetched, imported, skipped, errors.size());

        return new SyncResult(connector.providerName(), account, effectiveSince, connector.isSandbox(),
                pages, fetched, imported, skipped, errors.size(), errors, clock.instant());
    }

    // ---------------------------------------------------------------- internals

    /**
     * Returns a usable access token, refreshing first when the stored one is
     * expired or about to be. A failed refresh marks the credential EXPIRED so the
     * UI can prompt for re-authorisation instead of failing the same way forever.
     */
    String ensureFreshAccessToken(AccountingConnector connector, ConnectorCredential credential) {
        Instant expiresAt = credential.getAccessTokenExpiresAt();
        boolean needsRefresh = expiresAt != null
                && expiresAt.minusSeconds(REFRESH_SKEW_SECONDS).isBefore(clock.instant());

        if (!needsRefresh) {
            return tokenCipher.decrypt(credential.getAccessToken());
        }
        if (credential.getRefreshToken() == null) {
            credential.setStatus(ConnectorStatus.EXPIRED);
            credentialRepository.save(credential);
            throw new ConnectorException("Access token for " + connector.providerName()
                    + " has expired and no refresh token is stored; re-authorise the connection");
        }

        try {
            OAuthTokenResponse refreshed = connector.refreshAccessToken(
                    tokenCipher.decrypt(credential.getRefreshToken()));
            applyToken(credential, refreshed);
            credential.setStatus(ConnectorStatus.CONNECTED);
            credentialRepository.save(credential);
            return refreshed.accessToken();
        } catch (RuntimeException e) {
            credential.setStatus(ConnectorStatus.EXPIRED);
            credential.setLastSyncError(truncate("Token refresh failed: " + e.getMessage(), 1000));
            credentialRepository.save(credential);
            throw new ConnectorException("Could not refresh the " + connector.providerName()
                    + " access token; re-authorise the connection", e);
        }
    }

    /** Encrypts and stores a token response. Rotation-safe: providers may or may not issue a new refresh token. */
    private void applyToken(ConnectorCredential credential, OAuthTokenResponse token) {
        Instant now = clock.instant();
        credential.setAccessToken(tokenCipher.encrypt(token.accessToken()));
        if (token.refreshToken() != null && !token.refreshToken().isBlank()) {
            credential.setRefreshToken(tokenCipher.encrypt(token.refreshToken()));
        }
        credential.setAccessTokenExpiresAt(
                token.expiresIn() == null ? null : now.plusSeconds(token.expiresIn()));
        credential.setRefreshTokenExpiresAt(
                token.refreshTokenExpiresIn() == null ? null : now.plusSeconds(token.refreshTokenExpiresIn()));
        if (token.scope() != null) {
            credential.setScope(truncate(token.scope(), 500));
        }
        credential.setUpdatedAt(now);
    }

    private Transaction toTransaction(String provider, ExternalTransaction external) {
        if (external.externalId() == null || external.externalId().isBlank()) {
            throw new IllegalArgumentException("provider returned a transaction with no external id");
        }
        if (external.amount() == null || external.postedDate() == null) {
            throw new IllegalArgumentException("provider returned a transaction with no amount or posted date");
        }
        return Transaction.builder()
                .source(provider)
                .account(external.account())
                .amount(external.amount())
                .currency(external.currency() == null ? "USD" : external.currency().toUpperCase())
                .postedDate(external.postedDate())
                .description(truncate(external.description(), 500))
                .category(truncate(external.category(), 100))
                // Connector rows land as POSTED, not PENDING: the source system has
                // already posted them. Reconciliation is what promotes them further.
                .status(TransactionStatus.POSTED)
                .externalId(truncate(external.externalId(), 150))
                .build();
    }

    /**
     * Resumes from one day before the last successful sync (not from the exact
     * timestamp), because providers commonly back-date entries by a day; the
     * (source, externalId) check makes the overlap free.
     */
    private LocalDate resolveSince(LocalDate requested, ConnectorCredential credential) {
        if (requested != null) {
            return requested;
        }
        if (credential.getLastSyncAt() != null) {
            return LocalDate.ofInstant(credential.getLastSyncAt(), ZoneOffset.UTC).minusDays(1);
        }
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(INITIAL_SYNC_LOOKBACK_DAYS);
    }

    private String resolveRealmId(AccountingConnector connector, String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        if (connector.isSandbox()) {
            return "sandbox-realm-" + connector.providerName();
        }
        return null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    /** Exposed for the controller's status lookup of a single pair. */
    @Transactional(readOnly = true)
    public Optional<ConnectorCredential> findCredential(String provider, String account) {
        return credentialRepository.findByProviderAndAccount(connector(provider).providerName(), account);
    }
}
