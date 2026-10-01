package com.zentrox.ledger.service;

import com.zentrox.ledger.dto.auth.AuthResponse;
import com.zentrox.ledger.dto.auth.LoginRequest;
import com.zentrox.ledger.dto.auth.RegisterRequest;
import com.zentrox.ledger.entity.Role;
import com.zentrox.ledger.entity.User;
import com.zentrox.ledger.exception.DuplicateResourceException;
import com.zentrox.ledger.repository.UserRepository;
import com.zentrox.ledger.security.JwtService;
import com.zentrox.ledger.security.SecurityUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    /** Authority string Spring Security derives from {@link Role#ADMIN}. */
    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Authorize the requested role BEFORE touching the user table, so an
        // anonymous caller probing for an ADMIN account cannot also use the
        // duplicate-username response to enumerate existing users.
        Role role = authorizeRequestedRole(request.role() == null ? Role.VIEWER : request.role());

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
                .role(role)
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

    /**
     * Decides whether the caller may create an account at {@code requested}.
     *
     * {@code POST /api/auth/register} is unauthenticated by necessity - it is how
     * the first account comes into being - but it used to honour whatever `role`
     * the body asked for, so anyone who could reach the API could mint themselves
     * an ADMIN token and read the audit trail, manage connectors and delete
     * ledger rows. The rules now are:
     *
     * <ol>
     *   <li>VIEWER is always allowed. That is what self-service signup creates.</li>
     *   <li>Any role is allowed while the user table is empty. There is no admin
     *       to ask yet, and this is the only way to bootstrap a deployment that
     *       runs with account seeding off. The window closes permanently as soon
     *       as one account exists.</li>
     *   <li>Otherwise the caller must present a valid ADMIN token.</li>
     * </ol>
     *
     * @throws AccessDeniedException mapped to 403 by GlobalExceptionHandler.
     */
    private Role authorizeRequestedRole(Role requested) {
        if (requested == Role.VIEWER) {
            return requested;
        }

        if (userRepository.count() == 0) {
            log.warn("Bootstrapping the first account with role {}: the user table was empty. "
                    + "Further privileged accounts now require an ADMIN token.", requested);
            return requested;
        }

        if (callerIsAdmin()) {
            return requested;
        }

        throw new AccessDeniedException(
                "Self-registration can only create a VIEWER account; an ADMIN must provision "
                        + requested + " accounts");
    }

    /**
     * True when the current request carries a valid ADMIN token.
     *
     * {@code /api/auth/**} is {@code permitAll}, but JwtAuthenticationFilter still
     * runs on it and populates the SecurityContext when an Authorization header is
     * present - so an admin calling this endpoint is recognisable here.
     */
    private boolean callerIsAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (ROLE_ADMIN.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
