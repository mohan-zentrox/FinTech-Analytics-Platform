package com.zentrox.ledger.fraud;

import com.zentrox.ledger.dto.common.PageResponse;
import com.zentrox.ledger.dto.fraud.AlertDecisionRequest;
import com.zentrox.ledger.dto.fraud.FraudAlertDto;
import com.zentrox.ledger.dto.fraud.FraudScanRequest;
import com.zentrox.ledger.dto.fraud.FraudScanResult;
import com.zentrox.ledger.entity.AlertSeverity;
import com.zentrox.ledger.entity.AlertStatus;
import com.zentrox.ledger.entity.FraudRule;
import com.zentrox.ledger.repository.spec.FraudAlertSpecifications;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * FRD S5.4 "Anomaly and Fraud Detection".
 *
 * Replaces the original scaffold, which reserved GET /api/fraud/alerts and
 * returned 501. The route contract it reserved is preserved and extended:
 * scanning, a filterable/paginated alert queue, and per-alert triage.
 */
@RestController
@RequestMapping("/api/fraud")
@RequiredArgsConstructor
public class FraudDetectionController {

    private static final int MAX_PAGE_SIZE = 200;

    private final FraudDetectionService fraudDetectionService;

    /**
     * POST /api/fraud/scan - run the rule set over a window and persist findings.
     * Safe to call repeatedly: findings are upserted per (transaction, rule).
     */
    @PostMapping("/scan")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public ResponseEntity<FraudScanResult> scan(
            @Valid @RequestBody(required = false) FraudScanRequest request) {
        return ResponseEntity.ok(fraudDetectionService.scan(
                request == null ? FraudScanRequest.all() : request));
    }

    /** GET /api/fraud/alerts?account=&status=&severity=&ruleId=&page=&size= */
    @GetMapping("/alerts")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public ResponseEntity<PageResponse<FraudAlertDto>> listAlerts(
            @RequestParam(required = false) String account,
            @RequestParam(required = false) AlertStatus status,
            @RequestParam(required = false) AlertSeverity severity,
            @RequestParam(required = false) FraudRule ruleId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        var pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "detectedAt"));
        var result = fraudDetectionService
                .searchAlerts(FraudAlertSpecifications.filter(account, status, severity, ruleId), pageable)
                .map(FraudAlertDto::from);
        return ResponseEntity.ok(PageResponse.from(result));
    }

    @GetMapping("/alerts/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public ResponseEntity<FraudAlertDto> getAlert(@PathVariable UUID id) {
        return ResponseEntity.ok(FraudAlertDto.from(fraudDetectionService.getAlert(id)));
    }

    /** PATCH /api/fraud/alerts/{id} - record a CONFIRMED / DISMISSED / re-OPENed decision. */
    @PatchMapping("/alerts/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public ResponseEntity<FraudAlertDto> decide(@PathVariable UUID id,
                                                @Valid @RequestBody AlertDecisionRequest request,
                                                Authentication authentication) {
        String actor = authentication == null ? "system" : authentication.getName();
        return ResponseEntity.ok(FraudAlertDto.from(fraudDetectionService.decide(id, request, actor)));
    }

    /** GET /api/fraud/summary?account= - open-alert count, for dashboard tiles. */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST','VIEWER')")
    public ResponseEntity<Map<String, Object>> summary(@RequestParam(required = false) String account) {
        return ResponseEntity.ok(Map.of(
                "account", account == null ? "" : account,
                "openAlerts", fraudDetectionService.countOpenAlerts(account)));
    }
}
