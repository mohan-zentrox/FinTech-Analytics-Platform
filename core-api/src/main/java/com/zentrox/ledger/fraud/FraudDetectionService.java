package com.zentrox.ledger.fraud;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * SCAFFOLD ONLY - no business logic implemented yet.
 *
 * TODO (FRD S5.4 "Anomaly & Fraud Detection"): implement rule-based and/or
 * statistical anomaly scoring over the transaction ledger, e.g.:
 *   - duplicate-payment detection (same payee/amount within a short window)
 *   - outlier amount detection per account/category (z-score / IQR)
 *   - velocity checks (unusually high transaction count/volume per period)
 *   - out-of-policy category/vendor combinations
 * TODO: decide whether scoring runs synchronously on import, or as an async
 * batch job (see reports.ReportGenerationService for an analogous scheduled
 * job pattern). Persisted findings should probably live in a new
 * `fraud_alert` table (actor, transactionId, ruleId, score, status).
 * TODO: expose configuration (thresholds per rule) via application.yml,
 * owned by T2-DATA1 (Financial Data Analyst) per BRD S4.2.
 */
@Service
public class FraudDetectionService {

    public List<Object> scanRecentTransactions(UUID accountId) {
        throw new UnsupportedOperationException(
                "FraudDetectionService.scanRecentTransactions is not implemented - see FRD S5.4");
    }
}
