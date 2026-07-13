package com.zentrox.ledger.repository;

import com.zentrox.ledger.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    Page<AuditLog> findByEntityAndEntityId(String entity, String entityId, Pageable pageable);
}
