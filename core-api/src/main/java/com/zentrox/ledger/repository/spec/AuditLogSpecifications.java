package com.zentrox.ledger.repository.spec;

import com.zentrox.ledger.entity.AuditLog;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;

/**
 * Null-safe filters for GET /api/audit-logs. Every field is optional so the
 * endpoint can serve both "the whole trail" (the compliance reviewer's view)
 * and "everything that touched this one entity" (the drill-down that the
 * original entity+entityId-only contract supported).
 */
public final class AuditLogSpecifications {

    private AuditLogSpecifications() {
    }

    public static Specification<AuditLog> filter(String entity, String entityId, String actor,
                                                 String action, Instant from, Instant to) {
        Specification<AuditLog> spec = Specification.where(null);

        if (isSet(entity)) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("entity"), entity));
        }
        if (isSet(entityId)) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("entityId"), entityId));
        }
        if (isSet(actor)) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("actor"), actor));
        }
        if (isSet(action)) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("action"), action.toUpperCase()));
        }
        if (from != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("timestamp"), from));
        }
        if (to != null) {
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("timestamp"), to));
        }
        return spec;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
