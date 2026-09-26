package com.zentrox.ledger.config;

import com.zentrox.ledger.entity.User;
import com.zentrox.ledger.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the demo accounts listed under `ledger.seed.users` when
 * `ledger.seed.enabled` is true.
 *
 * Design notes:
 *  - Idempotent. An account whose username already exists is skipped, never
 *    updated, so restarting the stack does not reset a password an operator has
 *    since changed, and a seeded account that was deliberately deleted does not
 *    silently reappear with a different id.
 *  - Passwords go through the same {@link PasswordEncoder} bean the login path
 *    verifies against, so a seeded account is indistinguishable from a
 *    registered one. Nothing here writes a hash by hand.
 *  - Runs as an {@link ApplicationRunner}, i.e. after Flyway has migrated and the
 *    context is up, so app_user is guaranteed to exist.
 *  - Never logs a password, and refuses to seed one that would fail the same
 *    length rule the registration endpoint enforces.
 */
@Component
@ConditionalOnProperty(prefix = "ledger.seed", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class DevDataSeeder implements ApplicationRunner {

    private final SeedProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (properties.getUsers().isEmpty()) {
            log.warn("Account seeding is enabled but ledger.seed.users is empty; nothing to create");
            return;
        }

        log.warn("=== Account seeding is ENABLED. The configured passwords are committed to the "
                + "repository and must be treated as public. Never enable this on an environment "
                + "holding real data. ===");

        int created = 0;
        int skipped = 0;

        for (SeedProperties.SeedUser seed : properties.getUsers()) {
            if (!isValid(seed)) {
                continue;
            }
            if (userRepository.existsByUsername(seed.getUsername())) {
                skipped++;
                continue;
            }
            if (userRepository.existsByEmail(seed.getEmail())) {
                // The username is free but the email is taken - creating this would
                // violate the unique constraint and abort startup.
                log.warn("Skipping seed account '{}': its email is already registered to another user",
                        seed.getUsername());
                skipped++;
                continue;
            }

            userRepository.save(User.builder()
                    .username(seed.getUsername())
                    .email(seed.getEmail())
                    .passwordHash(passwordEncoder.encode(seed.getPassword()))
                    .role(seed.getRole())
                    .build());
            created++;
            log.info("Seeded account '{}' with role {}", seed.getUsername(), seed.getRole());
        }

        log.info("Account seeding finished: {} created, {} already present", created, skipped);
    }

    /** Validates a seed entry, logging and skipping rather than failing startup over demo data. */
    private boolean isValid(SeedProperties.SeedUser seed) {
        if (seed.getUsername() == null || seed.getUsername().isBlank()
                || seed.getEmail() == null || seed.getEmail().isBlank()
                || seed.getRole() == null) {
            log.warn("Skipping malformed seed account: {}", seed);
            return false;
        }
        if (seed.getPassword() == null || seed.getPassword().length() < SeedProperties.MIN_PASSWORD_LENGTH) {
            log.warn("Skipping seed account '{}': password is shorter than the {}-character minimum",
                    seed.getUsername(), SeedProperties.MIN_PASSWORD_LENGTH);
            return false;
        }
        return true;
    }
}
