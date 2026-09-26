package com.zentrox.ledger.dto.fraud;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * POST /api/fraud/scan. Every field is optional:
 *  - account: restrict the scan to one account (default: every account)
 *  - dateFrom/dateTo: explicit window (default: the trailing
 *    ledger.fraud.lookback-days ending today)
 */
public record FraudScanRequest(
        @Size(max = 100) String account,
        LocalDate dateFrom,
        LocalDate dateTo
) {
    public static FraudScanRequest all() {
        return new FraudScanRequest(null, null, null);
    }
}
