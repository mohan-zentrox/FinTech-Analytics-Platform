package com.zentrox.ledger.service;

import com.zentrox.ledger.dto.auth.AuthResponse;
import com.zentrox.ledger.dto.auth.LoginRequest;
import com.zentrox.ledger.dto.auth.RegisterRequest;
import com.zentrox.ledger.entity.User;
import com.zentrox.ledger.exception.DuplicateResourceException;
import com.zentrox.ledger.repository.UserRepository;
import com.zentrox.ledger.security.JwtService;
import com.zentrox.ledger.security.SecurityUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new DuplicateResourceException("Username already taken: " + request.username());
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Email already registered: " + request.email());
        }

        User user = User.builder()
                .username(request.username())
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(request.role())
                .build();
        userRepository.save(user);

        SecurityUser securityUser = new SecurityUser(user);
        String token = jwtService.generateToken(securityUser, user.getRole().name());
        return AuthResponse.of(token, user.getUsername(), user.getRole(), jwtService.getExpirationMs());
    }

    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));

        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + request.username()));

        SecurityUser securityUser = new SecurityUser(user);
        String token = jwtService.generateToken(securityUser, user.getRole().name());
        return AuthResponse.of(token, user.getUsername(), user.getRole(), jwtService.getExpirationMs());
    }
}
