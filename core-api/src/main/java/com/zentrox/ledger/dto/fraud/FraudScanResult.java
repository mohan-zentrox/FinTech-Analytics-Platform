package com.zentrox.ledger.dto.fraud;

import com.zentrox.ledger.entity.FraudRule;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Outcome of one fraud scan. `newAlerts` vs `updatedAlerts` makes a re-scan's
 * idempotency visible to the caller: scanning the same window twice reports
 * zero new alerts the second time.
 */
public record FraudScanResult(
        UUID scanId,
        String account,
        LocalDate dateFrom,
        LocalDate dateTo,
        int transactionsScanned,
        int findings,
        int newAlerts,
        int updatedAlerts,
        int suppressedAlerts,
        Map<FraudRule, Integer> findingsByRule
) {
}
