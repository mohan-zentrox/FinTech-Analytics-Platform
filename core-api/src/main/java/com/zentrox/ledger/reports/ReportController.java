package com.zentrox.ledger.reports;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SCAFFOLD ONLY - see ReportGenerationService for the FRD S6.1 requirements
 * this controller will eventually expose.
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportGenerationService reportGenerationService;

    // TODO (FRD S6.1): POST /api/reports/cash-flow/generate?accountId=&format=pdf|xlsx
    @PostMapping("/cash-flow/generate")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public ResponseEntity<Void> generateCashFlowReport(@RequestParam String accountId) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
