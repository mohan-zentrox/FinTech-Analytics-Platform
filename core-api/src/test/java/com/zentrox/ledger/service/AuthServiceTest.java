package com.zentrox.ledger.service;

import com.zentrox.ledger.dto.auth.AuthResponse;
import com.zentrox.ledger.dto.auth.LoginRequest;
import com.zentrox.ledger.dto.auth.RegisterRequest;
import com.zentrox.ledger.entity.Role;
import com.zentrox.ledger.entity.User;
import com.zentrox.ledger.exception.DuplicateResourceException;
import com.zentrox.ledger.repository.UserRepository;
import com.zentrox.ledger.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private AuthenticationManager authenticationManager;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtService, authenticationManager);
    }

    @Test
    void registerThrowsWhenUsernameTaken() {
        RegisterRequest request = new RegisterRequest("alice", "alice@example.com", "password123", Role.VIEWER);
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request)).isInstanceOf(DuplicateResourceException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void registerHashesPasswordAndReturnsToken() {
        RegisterRequest request = new RegisterRequest("alice", "alice@example.com", "password123", Role.ANALYST);
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed-password");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.generateToken(any(UserDetails.class), eq("ANALYST"))).thenReturn("signed.jwt.token");
        when(jwtService.getExpirationMs()).thenReturn(3600_000L);

        AuthResponse response = authService.register(request);

        assertThat(response.token()).isEqualTo("signed.jwt.token");
        assertThat(response.username()).isEqualTo("alice");
        assertThat(response.role()).isEqualTo(Role.ANALYST);
        verify(passwordEncoder).encode("password123");
    }

    @Test
    void loginAuthenticatesAndReturnsToken() {
        User user = User.builder()
                .username("bob").email("bob@example.com").passwordHash("hashed").role(Role.VIEWER).build();
        LoginRequest request = new LoginRequest("bob", "password123");

        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(user));
        when(jwtService.generateToken(any(UserDetails.class), eq("VIEWER"))).thenReturn("signed.jwt.token");
        when(jwtService.getExpirationMs()).thenReturn(3600_000L);

        AuthResponse response = authService.login(request);

        assertThat(response.token()).isEqualTo("signed.jwt.token");
        verify(authenticationManager).authenticate(any());
    }
}
