package com.zentrox.ledger.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Stored OAuth2 credentials for one (provider, account) pair (FRD S6.2).
 *
 * The token columns hold ciphertext, not the raw tokens - see
 * {@code connector.TokenCipher}. Nothing in this entity decrypts anything; the
 * service layer does, so a stray {@code toString()} or a database dump cannot
 * leak a usable third-party credential.
 */
@Entity
@Table(name = "connector_credential",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_connector_credential_provider_account",
                columnNames = {"provider", "account"}),
        indexes = @Index(name = "idx_connector_credential_provider", columnList = "provider"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConnectorCredential {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 50)
    private String provider;

    @Column(nullable = false, length = 100)
    private String account;

    /** Provider-side tenant identifier: QuickBooks realmId, Xero tenantId. */
    @Column(name = "realm_id", length = 100)
    private String realmId;

    @Column(name = "access_token", nullable = false, columnDefinition = "TEXT")
    private String accessToken;

    @Column(name = "refresh_token", columnDefinition = "TEXT")
    private String refreshToken;

    @Column(name = "access_token_expires_at")
    private Instant accessTokenExpiresAt;

    @Column(name = "refresh_token_expires_at")
    private Instant refreshTokenExpiresAt;

    @Column(length = 500)
    private String scope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConnectorStatus status;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    /** Opaque provider cursor / high-water mark so the next sync is incremental. */
    @Column(name = "last_sync_cursor", length = 200)
    private String lastSyncCursor;

    @Column(name = "last_sync_error", length = 1000)
    private String lastSyncError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (status == null) {
            status = ConnectorStatus.CONNECTED;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Excludes every token field - this entity must never log its own secrets. */
    @Override
    public String toString() {
        return "ConnectorCredential{provider=" + provider + ", account=" + account
                + ", status=" + status + ", lastSyncAt=" + lastSyncAt + "}";
    }
}
