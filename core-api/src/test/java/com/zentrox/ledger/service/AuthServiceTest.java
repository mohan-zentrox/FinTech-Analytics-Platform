package com.zentrox.ledger.service;

import com.zentrox.ledger.dto.auth.AuthResponse;
import com.zentrox.ledger.dto.auth.LoginRequest;
import com.zentrox.ledger.dto.auth.RegisterRequest;
import com.zentrox.ledger.entity.Role;
import com.zentrox.ledger.entity.User;
import com.zentrox.ledger.exception.DuplicateResourceException;
import com.zentrox.ledger.repository.UserRepository;
import com.zentrox.ledger.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
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

    @AfterEach
    void clearSecurityContext() {
        // SecurityContextHolder is a thread-local; a leaked admin authentication
        // would silently grant the next test privileges it never asked for.
        SecurityContextHolder.clearContext();
    }

    /** Puts an authenticated ADMIN in the SecurityContext, as JwtAuthenticationFilter would. */
    private void authenticateAsAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "an-admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
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
        // Empty user table -> first-run bootstrap, so the privileged role is allowed.
        when(userRepository.count()).thenReturn(0L);
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

    @Test
    void registerDefaultsToViewerWhenRoleIsOmitted() {
        RegisterRequest request = new RegisterRequest("erin", "erin@example.com", "password123", null);
        when(userRepository.existsByUsername("erin")).thenReturn(false);
        when(userRepository.existsByEmail("erin@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed-password");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.generateToken(any(UserDetails.class), eq("VIEWER"))).thenReturn("signed.jwt.token");
        when(jwtService.getExpirationMs()).thenReturn(3600_000L);

        AuthResponse response = authService.register(request);

        assertThat(response.role()).isEqualTo(Role.VIEWER);
    }

    @Test
    void registerRejectsAPrivilegedRoleFromAnAnonymousCaller() {
        // The vulnerability this guards: an unauthenticated POST /api/auth/register
        // with "role":"ADMIN" used to return a working ADMIN token.
        RegisterRequest request = new RegisterRequest("mallory", "mallory@evil.test", "password123", Role.ADMIN);
        when(userRepository.count()).thenReturn(4L);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("VIEWER");

        verify(userRepository, never()).save(any());
    }

    @Test
    void registerRejectsAPrivilegedRoleFromANonAdminCaller() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "an-analyst", null, List.of(new SimpleGrantedAuthority("ROLE_ANALYST"))));
        RegisterRequest request = new RegisterRequest("mallory", "mallory@evil.test", "password123", Role.ANALYST);
        when(userRepository.count()).thenReturn(4L);

        assertThatThrownBy(() -> authService.register(request)).isInstanceOf(AccessDeniedException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void registerAllowsSelfServiceViewerSignupOnAPopulatedTable() {
        RegisterRequest request = new RegisterRequest("frank", "frank@example.com", "password123", Role.VIEWER);
        when(userRepository.existsByUsername("frank")).thenReturn(false);
        when(userRepository.existsByEmail("frank@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed-password");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.generateToken(any(UserDetails.class), eq("VIEWER"))).thenReturn("signed.jwt.token");
        when(jwtService.getExpirationMs()).thenReturn(3600_000L);

        AuthResponse response = authService.register(request);

        assertThat(response.role()).isEqualTo(Role.VIEWER);
        // count() must not even be consulted: VIEWER is always permitted.
        verify(userRepository, never()).count();
    }

    @Test
    void registerAllowsAPrivilegedRoleForAnAdminCaller() {
        authenticateAsAdmin();
        RegisterRequest request = new RegisterRequest("grace", "grace@example.com", "password123", Role.ADMIN);
        when(userRepository.count()).thenReturn(4L);
        when(userRepository.existsByUsername("grace")).thenReturn(false);
        when(userRepository.existsByEmail("grace@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed-password");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.generateToken(any(UserDetails.class), eq("ADMIN"))).thenReturn("signed.jwt.token");
        when(jwtService.getExpirationMs()).thenReturn(3600_000L);

        AuthResponse response = authService.register(request);

        assertThat(response.role()).isEqualTo(Role.ADMIN);
    }
}
