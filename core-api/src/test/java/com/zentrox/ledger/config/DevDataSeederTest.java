package com.zentrox.ledger.config;

import com.zentrox.ledger.entity.Role;
import com.zentrox.ledger.entity.User;
import com.zentrox.ledger.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DevDataSeederTest {

    @Mock
    private UserRepository userRepository;

    private PasswordEncoder passwordEncoder;
    private SeedProperties properties;
    private DevDataSeeder seeder;

    @BeforeEach
    void setUp() {
        // The real encoder, not a mock: the point of several of these tests is that
        // a seeded password actually verifies through the same encoder login uses.
        passwordEncoder = new BCryptPasswordEncoder();
        properties = new SeedProperties();
        seeder = new DevDataSeeder(properties, userRepository, passwordEncoder);

        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private SeedProperties.SeedUser user(String username, String email, String password, Role role) {
        SeedProperties.SeedUser seed = new SeedProperties.SeedUser();
        seed.setUsername(username);
        seed.setEmail(email);
        seed.setPassword(password);
        seed.setRole(role);
        return seed;
    }

    private void withUsers(SeedProperties.SeedUser... users) {
        properties.setUsers(new java.util.ArrayList<>(List.of(users)));
    }

    @Test
    void createsEveryConfiguredAccount() {
        withUsers(
                user("admin", "admin@projectledger.local", "LedgerAdmin#2026", Role.ADMIN),
                user("auditor", "auditor@projectledger.local", "LedgerAudit#2026", Role.ADMIN),
                user("analyst", "analyst@projectledger.local", "LedgerAnalyst#2026", Role.ANALYST),
                user("viewer", "viewer@projectledger.local", "LedgerViewer#2026", Role.VIEWER));

        seeder.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(4)).save(captor.capture());

        assertThat(captor.getAllValues()).extracting(User::getUsername)
                .containsExactly("admin", "auditor", "analyst", "viewer");
        assertThat(captor.getAllValues()).extracting(User::getRole)
                .containsExactly(Role.ADMIN, Role.ADMIN, Role.ANALYST, Role.VIEWER);
    }

    @Test
    void storesAHashThatTheLoginEncoderAccepts() {
        withUsers(user("admin", "admin@projectledger.local", "LedgerAdmin#2026", Role.ADMIN));

        seeder.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        String hash = captor.getValue().getPasswordHash();

        assertThat(hash).isNotEqualTo("LedgerAdmin#2026");
        assertThat(hash).startsWith("$2a$");
        // The property that actually matters: this is what the login path checks.
        assertThat(passwordEncoder.matches("LedgerAdmin#2026", hash)).isTrue();
        assertThat(passwordEncoder.matches("wrong-password", hash)).isFalse();
    }

    @Test
    void skipsAnAccountThatAlreadyExistsRatherThanOverwritingIt() {
        withUsers(user("admin", "admin@projectledger.local", "LedgerAdmin#2026", Role.ADMIN));
        when(userRepository.existsByUsername("admin")).thenReturn(true);

        seeder.run(null);

        // Re-running must not reset a password an operator has since changed.
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void isIdempotentAcrossRestarts() {
        withUsers(
                user("admin", "admin@projectledger.local", "LedgerAdmin#2026", Role.ADMIN),
                user("viewer", "viewer@projectledger.local", "LedgerViewer#2026", Role.VIEWER));

        seeder.run(null);
        verify(userRepository, times(2)).save(any(User.class));

        // Second start: both now exist.
        when(userRepository.existsByUsername(anyString())).thenReturn(true);
        seeder.run(null);

        verifyNoMoreInteractions(ignoreStubs(userRepository));
    }

    @Test
    void skipsAnAccountWhoseEmailBelongsToSomeoneElse() {
        withUsers(user("admin", "taken@projectledger.local", "LedgerAdmin#2026", Role.ADMIN));
        when(userRepository.existsByEmail("taken@projectledger.local")).thenReturn(true);

        seeder.run(null);

        // Saving would violate uk_app_user_email and abort startup.
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void skipsAPasswordShorterThanTheRegistrationMinimum() {
        withUsers(
                user("weak", "weak@projectledger.local", "short", Role.VIEWER),
                user("fine", "fine@projectledger.local", "LongEnough1", Role.VIEWER));

        seeder.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("fine");
    }

    @Test
    void skipsMalformedEntriesWithoutFailingStartup() {
        withUsers(
                user(null, "a@b.local", "LongEnough1", Role.ADMIN),
                user("no-email", null, "LongEnough1", Role.ADMIN),
                user("no-role", "c@b.local", "LongEnough1", null),
                user("good", "good@b.local", "LongEnough1", Role.ADMIN));

        seeder.run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("good");
    }

    @Test
    void doesNothingWhenNoAccountsAreConfigured() {
        properties.setUsers(new java.util.ArrayList<>());

        seeder.run(null);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void seedUserNeverRendersItsPassword() {
        // Guards against a password reaching a log line via a malformed-entry warning.
        SeedProperties.SeedUser seed = user("admin", "a@b.local", "SuperSecret123", Role.ADMIN);

        assertThat(seed.toString()).doesNotContain("SuperSecret123").contains("***");
    }
}
