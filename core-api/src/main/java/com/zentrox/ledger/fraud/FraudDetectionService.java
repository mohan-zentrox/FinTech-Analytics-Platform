package com.zentrox.ledger.fraud;

import com.zentrox.ledger.aspect.Audited;
import com.zentrox.ledger.dto.fraud.AlertDecisionRequest;
import com.zentrox.ledger.dto.fraud.FraudScanRequest;
import com.zentrox.ledger.dto.fraud.FraudScanResult;
import com.zentrox.ledger.entity.AlertStatus;
import com.zentrox.ledger.entity.FraudAlert;
import com.zentrox.ledger.entity.FraudRule;
import com.zentrox.ledger.entity.Transaction;
import com.zentrox.ledger.entity.TransactionStatus;
import com.zentrox.ledger.exception.ResourceNotFoundException;
import com.zentrox.ledger.repository.FraudAlertRepository;
import com.zentrox.ledger.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * FRD S5.4 "Anomaly and Fraud Detection" - scan orchestration and alert
 * lifecycle.
 *
 * Responsibilities kept here (as opposed to in {@link FraudRuleEngine}):
 * loading the window of transactions, upserting findings into fraud_alert,
 * honouring analyst decisions across re-scans, and optionally flagging the
 * offending transactions.
 *
 * Re-scan semantics: a finding for a (transaction, rule) pair that already has
 * a CONFIRMED or DISMISSED alert is *suppressed*, never resurrected as OPEN -
 * otherwise every scheduled scan would undo the review queue's triage work. An
 * existing OPEN alert has its score, severity and reason refreshed in place.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FraudDetectionService {

    private final TransactionRepository transactionRepository;
    private final FraudAlertRepository fraudAlertRepository;
    private final FraudRuleEngine ruleEngine;
    private final FraudProperties properties;
    private final Clock clock;

    /**
     * Runs every rule over the requested window and persists the results.
     *
     * @return counts that let the caller see what changed, including how many
     *         findings were suppressed by an earlier analyst decision.
     */
    @Audited(action = "SCAN", entity = "FraudAlert")
    @Transactional
    public FraudScanResult scan(FraudScanRequest request) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate dateTo = request.dateTo() == null ? today : request.dateTo();
        LocalDate dateFrom = request.dateFrom() == null
                ? dateTo.minusDays(properties.getLookbackDays())
                : request.dateFrom();

        if (dateFrom.isAfter(dateTo)) {
            throw new IllegalArgumentException("dateFrom must not be after dateTo");
        }

        List<Transaction> transactions = loadWindow(request.account(), dateFrom, dateTo);
        List<FraudFinding> findings = ruleEngine.evaluate(transactions, dateTo);

        UUID scanId = UUID.randomUUID();
        Instant now = clock.instant();
        int created = 0;
        int updated = 0;
        int suppressed = 0;
        Map<FraudRule, Integer> byRule = new EnumMap<>(FraudRule.class);
        Set<UUID> transactionsToFlag = new HashSet<>();

        for (FraudFinding finding : findings) {
            byRule.merge(finding.rule(), 1, Integer::sum);

            Optional<FraudAlert> existing = fraudAlertRepository
                    .findByTransactionIdAndRuleId(finding.transactionId(), finding.rule());

            if (existing.isPresent()) {
                FraudAlert alert = existing.get();
                if (alert.getStatus() != AlertStatus.OPEN) {
                    // Already triaged by an analyst - leave their decision alone.
                    suppressed++;
                    continue;
                }
                alert.setScanId(scanId);
                alert.setSeverity(finding.severity());
                alert.setScore(finding.score());
                alert.setReason(finding.reason());
                alert.setDetectedAt(now);
                fraudAlertRepository.save(alert);
                updated++;
            } else {
                fraudAlertRepository.save(FraudAlert.builder()
                        .scanId(scanId)
                        .transactionId(finding.transactionId())
                        .account(finding.account())
                        .ruleId(finding.rule())
                        .severity(finding.severity())
                        .score(finding.score())
                        .reason(finding.reason())
                        .status(AlertStatus.OPEN)
                        .detectedAt(now)
                        .build());
                created++;
            }
            transactionsToFlag.add(finding.transactionId());
        }

        if (properties.isAutoFlagTransactions() && !transactionsToFlag.isEmpty()) {
            flagTransactions(transactions, transactionsToFlag);
        }

        log.info("Fraud scan {} over {}..{} ({}): {} transactions, {} findings ({} new, {} updated, {} suppressed)",
                scanId, dateFrom, dateTo,
                request.account() == null ? "all accounts" : request.account(),
                transactions.size(), findings.size(), created, updated, suppressed);

        return new FraudScanResult(scanId, request.account(), dateFrom, dateTo,
                transactions.size(), findings.size(), created, updated, suppressed, byRule);
    }

    @Transactional(readOnly = true)
    public Page<FraudAlert> searchAlerts(Specification<FraudAlert> spec, Pageable pageable) {
        return fraudAlertRepository.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public FraudAlert getAlert(UUID id) {
        return fraudAlertRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Fraud alert not found: " + id));
    }

    /**
     * Records an analyst's CONFIRMED/DISMISSED decision (or re-opens an alert).
     * Audited, because this is the control that a compliance reviewer will be
     * asked to evidence.
     */
    @Audited(action = "TRIAGE", entity = "FraudAlert")
    @Transactional
    public FraudAlert decide(UUID id, AlertDecisionRequest request, String actor) {
        FraudAlert alert = getAlert(id);
        alert.setStatus(request.status());
        alert.setResolutionNote(request.resolutionNote());

        if (request.status() == AlertStatus.OPEN) {
            alert.setResolvedAt(null);
            alert.setResolvedBy(null);
        } else {
            alert.setResolvedAt(clock.instant());
            alert.setResolvedBy(actor);
        }
        return fraudAlertRepository.save(alert);
    }

    @Transactional(readOnly = true)
    public long countOpenAlerts(String account) {
        return account == null || account.isBlank()
                ? fraudAlertRepository.countByStatus(AlertStatus.OPEN)
                : fraudAlertRepository.countByAccountAndStatus(account, AlertStatus.OPEN);
    }

    private List<Transaction> loadWindow(String account, LocalDate dateFrom, LocalDate dateTo) {
        if (account != null && !account.isBlank()) {
            return transactionRepository.findByAccountAndPostedDateBetween(account, dateFrom, dateTo);
        }
        return transactionRepository.findByPostedDateBetween(dateFrom, dateTo);
    }

    /**
     * Marks the offending transactions FLAGGED. VOID rows are skipped (a
     * reversed entry is not worth flagging) and already-FLAGGED rows are left
     * untouched so a re-scan does not churn updated_at on every run. A
     * RECONCILED row *is* flagged: a matched transaction that also looks
     * anomalous is precisely the case worth surfacing.
     */
    private void flagTransactions(List<Transaction> scanned, Set<UUID> ids) {
        for (Transaction tx : scanned) {
            if (!ids.contains(tx.getId())) {
                continue;
            }
            if (tx.getStatus() == TransactionStatus.VOID || tx.getStatus() == TransactionStatus.FLAGGED) {
                continue;
            }
            tx.setStatus(TransactionStatus.FLAGGED);
            transactionRepository.save(tx);
        }
    }
}
