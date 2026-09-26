package com.zentrox.ledger.config;

import com.zentrox.ledger.entity.Role;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Demo accounts created at startup by {@link DevDataSeeder}, bound from
 * `ledger.seed.*`.
 *
 * Disabled by default. docker-compose turns it on for the local demo stack; no
 * other deployment does, so a real environment never gets these accounts unless
 * someone deliberately sets LEDGER_SEED_ENABLED=true.
 *
 * Every password has an env-var override, so a shared demo environment can seed
 * the same four personas without using the well-known passwords committed here.
 */
@Component
@ConfigurationProperties(prefix = "ledger.seed")
@Getter
@Setter
public class SeedProperties {

    /** Minimum password length, matching the RegisterRequest validation constraint. */
    public static final int MIN_PASSWORD_LENGTH = 8;

    /** When false (the default), no accounts are created and the seeder is not even registered. */
    private boolean enabled = false;

    private List<SeedUser> users = new ArrayList<>();

    @Getter
    @Setter
    public static class SeedUser {
        private String username;
        private String email;
        private String password;
        private Role role;

        /** Never let a password reach a log line or an exception message. */
        @Override
        public String toString() {
            return "SeedUser{username=" + username + ", role=" + role + ", password=***}";
        }
    }
}
