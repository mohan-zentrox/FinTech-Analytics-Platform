package com.zentrox.ledger.dto.fraud;

import com.zentrox.ledger.entity.AlertSeverity;
import com.zentrox.ledger.entity.AlertStatus;
import com.zentrox.ledger.entity.FraudAlert;
import com.zentrox.ledger.entity.FraudRule;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record FraudAlertDto(
        UUID id,
        UUID scanId,
        UUID transactionId,
        String account,
        FraudRule ruleId,
        AlertSeverity severity,
        BigDecimal score,
        String reason,
        AlertStatus status,
        Instant detectedAt,
        Instant resolvedAt,
        String resolvedBy,
        String resolutionNote
) {
    public static FraudAlertDto from(FraudAlert alert) {
        return new FraudAlertDto(
                alert.getId(),
                alert.getScanId(),
                alert.getTransactionId(),
                alert.getAccount(),
                alert.getRuleId(),
                alert.getSeverity(),
                alert.getScore(),
                alert.getReason(),
                alert.getStatus(),
                alert.getDetectedAt(),
                alert.getResolvedAt(),
                alert.getResolvedBy(),
                alert.getResolutionNote()
        );
    }
}
