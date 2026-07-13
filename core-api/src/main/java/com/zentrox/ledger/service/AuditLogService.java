package com.zentrox.ledger.service;

import com.zentrox.ledger.entity.AuditLog;
import com.zentrox.ledger.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    /**
     * Persisted in its own (new) transaction so an audit-log failure never
     * rolls back, and a rollback of the business transaction never
     * suppresses the audit record's own outcome.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actor, String action, String entity, String entityId, String details) {
        AuditLog log = AuditLog.builder()
                .actor(actor == null ? "system" : actor)
                .action(action)
                .entity(entity)
                .entityId(entityId)
                .details(details)
                .build();
        auditLogRepository.save(log);
    }
}
