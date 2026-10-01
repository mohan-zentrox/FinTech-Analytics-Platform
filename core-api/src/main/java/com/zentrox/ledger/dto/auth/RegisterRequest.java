package com.zentrox.ledger.dto.auth;

import com.zentrox.ledger.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registration payload.
 *
 * `role` is optional and defaults to {@link Role#VIEWER}. It is deliberately NOT
 * {@code @NotNull}: self-service registration may only ever create a VIEWER, so
 * the field exists for ADMIN-driven provisioning and for first-run bootstrap.
 * AuthService rejects a privileged role from an unprivileged caller with 403 -
 * see {@code AuthService#authorizeRequestedRole}.
 */
public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 100) String username,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 100) String password,
        Role role
) {
}
