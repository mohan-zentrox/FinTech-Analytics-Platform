package com.zentrox.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class LedgerApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the full Spring context (security, JPA, AOP, controllers) wires up cleanly.
    }
}
