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

    @Test
    void constructorRejectsShortSecret() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> new JwtService("too-short", 3600_000L));
    }
}
