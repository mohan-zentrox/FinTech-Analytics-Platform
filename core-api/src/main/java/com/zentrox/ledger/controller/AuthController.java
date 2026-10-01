package com.zentrox.ledger.controller;

import com.zentrox.ledger.dto.auth.AuthResponse;
import com.zentrox.ledger.dto.auth.LoginRequest;
import com.zentrox.ledger.dto.auth.RegisterRequest;
import com.zentrox.ledger.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Registration and login. Issues JWTs consumed by every other endpoint via
 * the Authorization: Bearer <token> header (see JwtAuthenticationFilter).
 *
 * Registration is open but NOT privilege-granting: `role` is optional and
 * self-service signup always yields VIEWER. Creating an ANALYST or ADMIN
 * requires an ADMIN bearer token, except on a completely empty user table,
 * where the first account may claim any role so a deployment running without
 * account seeding can bootstrap itself. See AuthService#authorizeRequestedRole.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }
}
