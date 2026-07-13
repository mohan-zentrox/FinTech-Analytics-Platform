package com.zentrox.ledger.controller;

import com.zentrox.ledger.dto.reconciliation.ReconciliationRequest;
import com.zentrox.ledger.dto.reconciliation.ReconciliationResult;
import com.zentrox.ledger.service.ReconciliationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reconciliation")
@RequiredArgsConstructor
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    /** POST /api/reconciliation/run - see ReconciliationRequest for the two-set + tolerance contract. */
    @PostMapping("/run")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public ResponseEntity<ReconciliationResult> run(@Valid @RequestBody ReconciliationRequest request) {
        return ResponseEntity.ok(reconciliationService.run(request));
    }
}
