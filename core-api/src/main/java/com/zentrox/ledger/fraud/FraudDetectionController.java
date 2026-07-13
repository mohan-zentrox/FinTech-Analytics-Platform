package com.zentrox.ledger.fraud;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * SCAFFOLD ONLY - see FraudDetectionService for the FRD S5.4 requirements
 * this controller will eventually expose. Wired into Spring Security but
 * returns 501 until implemented so the route contract is reserved.
 */
@RestController
@RequestMapping("/api/fraud")
@RequiredArgsConstructor
public class FraudDetectionController {

    private final FraudDetectionService fraudDetectionService;

    // TODO (FRD S5.4): GET /api/fraud/alerts?accountId=&status= - paginated alert list
    @GetMapping("/alerts")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public ResponseEntity<Void> listAlerts(@RequestParam(required = false) UUID accountId) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
