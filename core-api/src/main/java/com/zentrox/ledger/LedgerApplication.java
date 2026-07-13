package com.zentrox.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Project Ledger - Core API.
 *
 * Spring Boot REST service providing:
 *  - JWT authentication and role-based access control (admin / analyst / viewer)
 *  - Transaction ledger CRUD, search, filtering and pagination
 *  - CSV import with idempotent de-duplication by (source, externalId)
 *  - Rule-based reconciliation between two transaction sets
 *  - Append-only audit logging of mutating requests
 *
 * See docs/ARCHITECTURE.md for the overall system diagram and
 * docs/API.md for the REST contract.
 */
@SpringBootApplication
@EnableScheduling
public class LedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
