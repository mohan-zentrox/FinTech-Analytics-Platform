package com.zentrox.ledger.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.MacAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.function.Function;

/**
 * Issues and validates JWTs signed with HS256. The signing key is derived
 * from the raw bytes of `jwt.secret` (configure via the JWT_SECRET env var
 * in production - see .env.example). Kept dependency-free from Spring
 * context wiring (plain constructor injection of @Value) so it is trivially
 * unit-testable.
 *
 * The algorithm is pinned explicitly to HS256 rather than left to jjwt's
 * default. jjwt selects the MAC variant from the *key length* - a 32-47 byte
 * secret yields HS256, 48-63 gives HS384 and 64+ gives HS512 - so the algorithm
 * of the issued token silently depended on how long an operator's JWT_SECRET
 * happened to be, contradicting this class's own documentation. It also broke
 * analytics-service, which verifies these tokens with the algorithm pinned to
 * HS256 (as it must be: accepting whatever the token's own header claims is the
 * classic JWT algorithm-confusion hole).
 */
@Component
public class JwtService {

    /** Pinned so the issued algorithm never varies with the configured secret's length. */
    private static final MacAlgorithm ALGORITHM = Jwts.SIG.HS256;

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms}") long expirationMs
    ) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("jwt.secret must be at least 32 bytes (256 bits) for HS256");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generateToken(UserDetails userDetails, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .subject(userDetails.getUsername())
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey, ALGORITHM)
                .compact();
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public String extractRole(String token) {
        return extractClaim(token, claims -> claims.get("role", String.class));
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            String username = extractUsername(token);
            return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        Claims claims = parseAllClaims(token);
        return resolver.apply(claims);
    }

    private Claims parseAllClaims(String token) {
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token);

            // Verifying with a SecretKey already rules out algorithm confusion between
            // MAC and public-key algorithms, but jjwt will still happily accept HS384
            // or HS512 signed with this same secret. Requiring the exact algorithm this
            // service issues means a token not produced by the current configuration is
            // rejected outright, which is also what keeps core-api and
            // analytics-service (pinned to HS256) from ever disagreeing.
            String actualAlgorithm = jws.getHeader().getAlgorithm();
            if (!ALGORITHM.getId().equals(actualAlgorithm)) {
                throw new UnsupportedJwtException(
                        "Unexpected JWT algorithm " + actualAlgorithm + "; expected " + ALGORITHM.getId());
            }
            return jws.getPayload();
        } catch (ExpiredJwtException e) {
            // still return claims so callers can decide (e.g. extractUsername on an expired token)
            return e.getClaims();
        }
    }
}
