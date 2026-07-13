package com.zentrox.ledger.connector;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * SCAFFOLD ONLY (FRD S6.2). Reserves the REST surface for accounting-source
 * OAuth connectors; each action currently 501s until a real provider
 * integration is implemented (see AccountingConnector + *ConnectorService).
 */
@RestController
@RequestMapping("/api/connectors")
@RequiredArgsConstructor
public class ConnectorController {

    private final List<AccountingConnector> connectors;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<String>> listProviders() {
        return ResponseEntity.ok(connectors.stream().map(AccountingConnector::providerName).toList());
    }

    // TODO (FRD S6.2): POST /api/connectors/{provider}/connect - OAuth2 authorization-code exchange
    @PostMapping("/{provider}/connect")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> connect(@PathVariable String provider, @RequestParam String accountId,
                                         @RequestParam String code) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    // TODO (FRD S6.2): POST /api/connectors/{provider}/sync - pull latest transactions
    @PostMapping("/{provider}/sync")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public ResponseEntity<Void> sync(@PathVariable String provider, @RequestParam String accountId) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
