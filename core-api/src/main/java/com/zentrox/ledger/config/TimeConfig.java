package com.zentrox.ledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Exposes the system clock as a bean so services that make time-dependent
 * decisions (fraud scan windows, report periods, OAuth token expiry) can be
 * unit-tested with a fixed clock instead of sleeping or asserting on
 * "roughly now".
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
