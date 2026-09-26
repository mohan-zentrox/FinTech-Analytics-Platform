package com.zentrox.ledger.repository.spec;

import com.zentrox.ledger.entity.AlertSeverity;
import com.zentrox.ledger.entity.AlertStatus;
import com.zentrox.ledger.entity.FraudAlert;
import com.zentrox.ledger.entity.FraudRule;
import org.springframework.data.jpa.domain.Specification;

/** Null-safe filters for GET /api/fraud/alerts. */
public final class FraudAlertSpecifications {

    private FraudAlertSpecifications() {
    }

    public static Specification<FraudAlert> filter(String account, AlertStatus status,
                                                   AlertSeverity severity, FraudRule ruleId) {
        Specification<FraudAlert> spec = Specification.where(null);

        if (account != null && !account.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("account"), account));
        }
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        if (severity != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("severity"), severity));
        }
        if (ruleId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("ruleId"), ruleId));
        }
        return spec;
    }
}
