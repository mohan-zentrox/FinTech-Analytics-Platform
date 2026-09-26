package com.zentrox.ledger.connector;

import com.zentrox.ledger.dto.connector.ConnectRequest;
import com.zentrox.ledger.dto.connector.ConnectorStatusDto;
import com.zentrox.ledger.dto.connector.SyncResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FRD S6.2 "Accounting Source Connectors".
 *
 * Replaces the original scaffold, whose three routes returned 501. All of them
 * are preserved (GET /api/connectors, POST /{provider}/connect,
 * POST /{provider}/sync) and joined by the authorize-URL and disconnect steps a
 * real OAuth flow needs.
 */
@RestController
@RequestMapping("/api/connectors")
@RequiredArgsConstructor
public class ConnectorController {

    private final ConnectorSyncService connectorSyncService;

    /** GET /api/connectors - per-provider connection state (never includes tokens). */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public ResponseEntity<List<ConnectorStatusDto>> list() {
        return ResponseEntity.ok(connectorSyncService.listStatuses());
    }

    /**
     * GET /api/connectors/{provider}/authorize-url - step 1 of the OAuth2
     * authorization-code flow. The caller redirects the administrator here; the
     * provider then calls back to the configured redirect URI with a code.
     *
     * `state` is generated server-side when not supplied, so a client cannot
     * accidentally omit CSRF protection.
     */
    @GetMapping("/{provider}/authorize-url")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> authorizeUrl(@PathVariable String provider,
                                                            @RequestParam(required = false) String state) {
        String effectiveState = state == null || state.isBlank() ? UUID.randomUUID().toString() : state;
        return ResponseEntity.ok(Map.of(
                "provider", provider,
                "state", effectiveState,
                "authorizationUrl", connectorSyncService.authorizationUrl(provider, effectiveState)));
    }

    /** POST /api/connectors/{provider}/connect - step 2: exchange the code for tokens. */
    @PostMapping("/{provider}/connect")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ConnectorStatusDto> connect(@PathVariable String provider,
                                                      @Valid @RequestBody ConnectRequest request) {
        return ResponseEntity.ok(connectorSyncService.connect(provider, request));
    }

    /** DELETE /api/connectors/{provider}?account= - forget the stored credentials. */
    @DeleteMapping("/{provider}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> disconnect(@PathVariable String provider, @RequestParam String account) {
        connectorSyncService.disconnect(provider, account);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/connectors/{provider}/sync?account=&since= - pull transactions into
     * the ledger. Idempotent: rows already present are skipped, not duplicated.
     */
    @PostMapping("/{provider}/sync")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public ResponseEntity<SyncResult> sync(
            @PathVariable String provider,
            @RequestParam String account,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since) {
        return ResponseEntity.ok(connectorSyncService.sync(provider, account, since));
    }
}
