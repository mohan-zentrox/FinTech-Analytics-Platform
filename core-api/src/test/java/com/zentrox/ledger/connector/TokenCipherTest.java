package com.zentrox.ledger.connector;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenCipherTest {

    private TokenCipher cipherWithKey(String key) {
        ConnectorProperties properties = new ConnectorProperties();
        properties.setEncryptionKey(key);
        return new TokenCipher(properties);
    }

    private TokenCipher cipher() {
        return cipherWithKey("unit-test-connector-encryption-key-0123456789");
    }

    @Test
    void roundTripsATokenThroughEncryptAndDecrypt() {
        TokenCipher cipher = cipher();
        String token = "eyJhbGciOiJSUzI1NiJ9.super-secret-refresh-token.signature";

        String encrypted = cipher.encrypt(token);

        assertThat(encrypted).isNotEqualTo(token);
        assertThat(encrypted).doesNotContain("super-secret");
        assertThat(cipher.decrypt(encrypted)).isEqualTo(token);
    }

    @Test
    void producesDifferentCiphertextEachTimeSoTokenReuseIsNotDetectableFromTheDatabase() {
        TokenCipher cipher = cipher();

        String first = cipher.encrypt("same-token");
        String second = cipher.encrypt("same-token");

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("same-token");
        assertThat(cipher.decrypt(second)).isEqualTo("same-token");
    }

    @Test
    void passesNullThrough() {
        assertThat(cipher().encrypt(null)).isNull();
        assertThat(cipher().decrypt(null)).isNull();
    }

    @Test
    void handlesEmptyAndUnicodeTokens() {
        TokenCipher cipher = cipher();

        assertThat(cipher.decrypt(cipher.encrypt(""))).isEmpty();
        assertThat(cipher.decrypt(cipher.encrypt("токен-ünïcode-✓"))).isEqualTo("токен-ünïcode-✓");
    }

    @Test
    void rejectsTamperedCiphertextInsteadOfReturningGarbage() {
        TokenCipher cipher = cipher();
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt("original-token"));
        // Flip a bit in the ciphertext body (past the 12-byte IV).
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to decrypt");
    }

    @Test
    void cannotDecryptWithADifferentKey() {
        String encrypted = cipherWithKey("first-connector-key-0123456789abcdef").encrypt("token");
        TokenCipher other = cipherWithKey("second-connector-key-0123456789abcdef");

        assertThatThrownBy(() -> other.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONNECTOR_ENCRYPTION_KEY");
    }

    @Test
    void rejectsCiphertextTooShortToContainAnIv() {
        TokenCipher cipher = cipher();
        String tooShort = Base64.getEncoder().encodeToString(new byte[8]);

        assertThatThrownBy(() -> cipher.decrypt(tooShort)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesToStartWithAWeakOrMissingKey() {
        assertThatThrownBy(() -> cipherWithKey("short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 16 characters");

        assertThatThrownBy(() -> cipherWithKey(null)).isInstanceOf(IllegalStateException.class);
    }
}
