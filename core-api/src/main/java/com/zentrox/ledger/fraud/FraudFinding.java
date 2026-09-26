package com.zentrox.ledger.fraud;

import com.zentrox.ledger.entity.AlertSeverity;
import com.zentrox.ledger.entity.FraudRule;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One anomaly detected by {@link FraudRuleEngine}, before it is persisted as a
 * {@link com.zentrox.ledger.entity.FraudAlert}. Keeping the engine's output a
 * plain record (rather than entities) is what lets the rules be unit-tested
 * against in-memory transaction lists with no database.
 */
public record FraudFinding(
        UUID transactionId,
        String account,
        FraudRule rule,
        AlertSeverity severity,
        BigDecimal score,
        String reason
) {
}
