package com.zentrox.ledger.dto.auth;

import com.zentrox.ledger.entity.Role;

public record AuthResponse(
        String token,
        String tokenType,
        String username,
        Role role,
        long expiresInMs
) {
    public static AuthResponse of(String token, String username, Role role, long expiresInMs) {
        return new AuthResponse(token, "Bearer", username, role, expiresInMs);
    }
}
