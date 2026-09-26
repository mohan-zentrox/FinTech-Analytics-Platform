package com.zentrox.ledger.connector;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for stored OAuth tokens (FRD S6.2).
 *
 * GCM rather than CBC so the ciphertext is authenticated: a tampered token
 * fails to decrypt instead of silently yielding garbage that would then be sent
 * to the provider. A fresh random 12-byte IV is generated per encryption and
 * prefixed to the ciphertext, so encrypting the same token twice never produces
 * the same output (IV reuse is the one thing that breaks GCM outright).
 *
 * The key is derived from `ledger.connectors.encryption-key` via SHA-256, which
 * lets operators supply a passphrase of any length while the cipher always gets
 * exactly 256 bits.
 */
@Component
public class TokenCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int MIN_KEY_MATERIAL_LENGTH = 16;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public TokenCipher(ConnectorProperties properties) {
        String material = properties.getEncryptionKey();
        if (material == null || material.length() < MIN_KEY_MATERIAL_LENGTH) {
            throw new IllegalStateException(
                    "ledger.connectors.encryption-key must be at least " + MIN_KEY_MATERIAL_LENGTH
                            + " characters; generate one with: openssl rand -base64 32");
        }
        this.key = new SecretKeySpec(sha256(material), "AES");
    }

    /** @return base64(iv || ciphertext || tag), or null for null input. */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = ByteBuffer.allocate(iv.length + ciphertext.length)
                    .put(iv)
                    .put(ciphertext)
                    .array();
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt connector token", e);
        }
    }

    /** Inverse of {@link #encrypt}; throws if the ciphertext was tampered with. */
    public String decrypt(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            if (combined.length <= IV_LENGTH_BYTES) {
                throw new IllegalStateException("Stored connector token is too short to be valid ciphertext");
            }
            byte[] iv = new byte[IV_LENGTH_BYTES];
            byte[] ciphertext = new byte[combined.length - IV_LENGTH_BYTES];
            ByteBuffer.wrap(combined).get(iv).get(ciphertext);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Failed to decrypt connector token - has CONNECTOR_ENCRYPTION_KEY changed since it was stored?", e);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
