package com.zentrox.ledger.controller;

import com.zentrox.ledger.dto.common.PageResponse;
import com.zentrox.ledger.entity.AuditLog;
import com.zentrox.ledger.repository.AuditLogRepository;
import com.zentrox.ledger.repository.spec.AuditLogSpecifications;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Read-only view over the append-only audit trail. Admin only.
 *
 * All filters are optional: `entity`/`entityId` used to be mandatory, which
 * made the endpoint useless for the actual compliance use case ("show me
 * everything that happened, newest first") because you had to already know the
 * id of the row you were looking for.
 */
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private static final int MAX_PAGE_SIZE = 200;

    private final AuditLogRepository auditLogRepository;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PageResponse<AuditLog>> search(
            @RequestParam(required = false) String entity,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        var pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "timestamp"));
        var result = auditLogRepository.findAll(
                AuditLogSpecifications.filter(entity, entityId, actor, action, from, to), pageable);
        return ResponseEntity.ok(PageResponse.from(result));
    }
}
