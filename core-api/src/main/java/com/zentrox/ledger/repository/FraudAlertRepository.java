package com.zentrox.ledger.repository;

import com.zentrox.ledger.entity.AlertStatus;
import com.zentrox.ledger.entity.FraudAlert;
import com.zentrox.ledger.entity.FraudRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FraudAlertRepository extends JpaRepository<FraudAlert, UUID>,
        JpaSpecificationExecutor<FraudAlert> {

    Optional<FraudAlert> findByTransactionIdAndRuleId(UUID transactionId, FraudRule ruleId);

    List<FraudAlert> findByScanId(UUID scanId);

    /** Backs the FRD S6.1 anomaly alert report. */
    List<FraudAlert> findByDetectedAtBetween(java.time.Instant from, java.time.Instant to);

    List<FraudAlert> findByAccountAndDetectedAtBetween(
            String account, java.time.Instant from, java.time.Instant to);

    long countByAccountAndStatus(String account, AlertStatus status);

    long countByStatus(AlertStatus status);
}
