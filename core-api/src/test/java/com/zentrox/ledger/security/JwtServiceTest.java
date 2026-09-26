package com.zentrox.ledger.security;

import com.zentrox.ledger.entity.Role;
import com.zentrox.ledger.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-key-must-be-at-least-32-bytes";

    private JwtService jwtService;
    private SecurityUser securityUser;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, 3600_000L);
        User user = User.builder()
                .username("alice")
                .email("alice@example.com")
                .passwordHash("hashed")
                .role(Role.ANALYST)
                .build();
        securityUser = new SecurityUser(user);
    }

    @Test
    void generatesTokenThatRoundTripsUsernameAndRole() {
        String token = jwtService.generateToken(securityUser, "ANALYST");

        assertThat(jwtService.extractUsername(token)).isEqualTo("alice");
        assertThat(jwtService.extractRole(token)).isEqualTo("ANALYST");
        assertThat(jwtService.isTokenValid(token, securityUser)).isTrue();
    }

    @Test
    void rejectsTokenForDifferentUser() {
        String token = jwtService.generateToken(securityUser, "ANALYST");

        User otherUser = User.builder()
                .username("bob")
                .email("bob@example.com")
                .passwordHash("hashed")
                .role(Role.VIEWER)
                .build();
        SecurityUser other = new SecurityUser(otherUser);

        assertThat(jwtService.isTokenValid(token, other)).isFalse();
    }

    @Test
    void rejectsExpiredToken() {
        JwtService shortLived = new JwtService(SECRET, -1000L); // already expired
        String token = shortLived.generateToken(securityUser, "ANALYST");

        assertThat(shortLived.isTokenValid(token, securityUser)).isFalse();
    }

    /**
     * The algorithm must not vary with the secret's length. jjwt's default
     * signWith(key) picks HS256/HS384/HS512 from the key size, so a 64-byte secret
     * silently produced HS512 tokens - which analytics-service (pinned to HS256)
     * then rejected, breaking every dashboard read.
     */
    @Test
    void alwaysIssuesHs256RegardlessOfSecretLength() {
        String[] secrets = {
                "exactly-thirty-two-bytes-secret!",                                   // 32 bytes -> was HS256
                "a-forty-eight-byte-secret-for-hs384-selection!!!",                   // 48 -> was HS384
                "a-sixty-four-byte-secret-that-jjwt-would-have-signed-with-hs512!",   // 64 -> was HS512
        };

        for (String secret : secrets) {
            String token = new JwtService(secret, 3600_000L).generateToken(securityUser, "ANALYST");
            String headerJson = new String(
                    java.util.Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))),
                    java.nio.charset.StandardCharsets.UTF_8);

            assertThat(headerJson)
                    .as("secret of %d bytes", secret.length())
                    .contains("\"HS256\"");
        }
    }

    @Test
    void rejectsATokenSignedWithADifferentAlgorithm() {
        // A 64-byte secret is long enough for HS512; a token signed that way must not
        // verify against the HS256-pinned parser.
        String secret = "a-sixty-four-byte-secret-that-jjwt-would-have-signed-with-hs512!";
        JwtService service = new JwtService(secret, 3600_000L);

        String hs512Token = io.jsonwebtoken.Jwts.builder()
                .subject("alice")
                .claim("role", "ANALYST")
                .expiration(new java.util.Date(System.currentTimeMillis() + 3600_000L))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        io.jsonwebtoken.Jwts.SIG.HS512)
                .compact();

        assertThat(service.isTokenValid(hs512Token, securityUser)).isFalse();
    }

    @Test
    void constructorRejectsShortSecret() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> new JwtService("too-short", 3600_000L));
    }
}
