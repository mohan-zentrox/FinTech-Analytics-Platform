package com.zentrox.ledger.service;

import com.zentrox.ledger.aspect.Audited;
import com.zentrox.ledger.dto.reconciliation.MatchedPairDto;
import com.zentrox.ledger.dto.reconciliation.ReconciliationRequest;
import com.zentrox.ledger.dto.reconciliation.ReconciliationResult;
import com.zentrox.ledger.dto.transaction.TransactionDto;
import com.zentrox.ledger.entity.MatchType;
import com.zentrox.ledger.entity.ReconciliationMatch;
import com.zentrox.ledger.entity.Transaction;
import com.zentrox.ledger.repository.ReconciliationMatchRepository;
import com.zentrox.ledger.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Simple rule-based reconciliation matcher: for two transaction sets
 * (e.g. internal ledger vs. bank statement, for the same account/date
 * range) greedily pairs transactions whose amount and posted date fall
 * within the given tolerances. Each transaction in set A is matched to at
 * most one transaction in set B (its closest by amount then date delta);
 * anything left over on either side is reported as an exception.
 *
 * Results (both matches and exceptions) are persisted to
 * reconciliation_match for auditability of past runs.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final TransactionRepository transactionRepository;
    private final ReconciliationMatchRepository matchRepository;

    @Audited(action = "RECONCILE", entity = "ReconciliationRun")
    @Transactional
    public ReconciliationResult run(ReconciliationRequest request) {
        List<Transaction> setA = transactionRepository.findByAccountAndSourceAndPostedDateBetween(
                request.accountA(), request.sourceA(), request.dateFrom(), request.dateTo());
        List<Transaction> setB = transactionRepository.findByAccountAndSourceAndPostedDateBetween(
                request.accountB(), request.sourceB(), request.dateFrom(), request.dateTo());

        MatchOutcome outcome = match(setA, setB, request.amountTolerance(), request.dateToleranceDays());

        UUID runId = UUID.randomUUID();
        persist(runId, outcome);

        List<MatchedPairDto> matchedDtos = outcome.matchedPairs.stream()
                .map(p -> new MatchedPairDto(
                        TransactionDto.from(p.a),
                        TransactionDto.from(p.b),
                        p.amountDelta,
                        p.dateDeltaDays))
                .toList();

        return new ReconciliationResult(
                runId,
                matchedDtos,
                outcome.exceptionsA.stream().map(TransactionDto::from).toList(),
                outcome.exceptionsB.stream().map(TransactionDto::from).toList(),
                matchedDtos.size(),
                outcome.exceptionsA.size() + outcome.exceptionsB.size()
        );
    }

    /**
     * Pure matching function, deliberately free of persistence/Spring
     * concerns so it can be unit-tested directly with in-memory lists.
     */
    public MatchOutcome match(List<Transaction> setA, List<Transaction> setB,
                               BigDecimal amountTolerance, int dateToleranceDays) {
        List<Transaction> remainingB = new ArrayList<>(setB);
        List<MatchedPair> matchedPairs = new ArrayList<>();
        List<Transaction> exceptionsA = new ArrayList<>();

        for (Transaction a : setA) {
            Transaction best = null;
            BigDecimal bestAmountDelta = null;
            long bestDateDelta = Long.MAX_VALUE;

            for (Transaction b : remainingB) {
                BigDecimal amountDelta = a.getAmount().subtract(b.getAmount()).abs();
                long dateDelta = Math.abs(ChronoUnit.DAYS.between(a.getPostedDate(), b.getPostedDate()));

                if (amountDelta.compareTo(amountTolerance) <= 0 && dateDelta <= dateToleranceDays) {
                    boolean better = best == null
                            || amountDelta.compareTo(bestAmountDelta) < 0
                            || (amountDelta.compareTo(bestAmountDelta) == 0 && dateDelta < bestDateDelta);
                    if (better) {
                        best = b;
                        bestAmountDelta = amountDelta;
                        bestDateDelta = dateDelta;
                    }
                }
            }

            if (best != null) {
                remainingB.remove(best);
                matchedPairs.add(new MatchedPair(a, best, bestAmountDelta, (int) bestDateDelta));
            } else {
                exceptionsA.add(a);
            }
        }

        return new MatchOutcome(matchedPairs, exceptionsA, remainingB);
    }

    private void persist(UUID runId, MatchOutcome outcome) {
        List<ReconciliationMatch> rows = new ArrayList<>();

        for (MatchedPair pair : outcome.matchedPairs) {
            rows.add(ReconciliationMatch.builder()
                    .runId(runId)
                    .transactionId(pair.a.getId())
                    .matchedTransactionId(pair.b.getId())
                    .matchType(MatchType.MATCHED)
                    .amountDelta(pair.amountDelta)
                    .dateDeltaDays(pair.dateDeltaDays)
                    .build());
        }
        for (Transaction tx : outcome.exceptionsA) {
            rows.add(ReconciliationMatch.builder()
                    .runId(runId)
                    .transactionId(tx.getId())
                    .matchType(MatchType.EXCEPTION)
                    .build());
        }
        for (Transaction tx : outcome.exceptionsB) {
            rows.add(ReconciliationMatch.builder()
                    .runId(runId)
                    .transactionId(tx.getId())
                    .matchType(MatchType.EXCEPTION)
                    .build());
        }

        matchRepository.saveAll(rows);
    }

    public record MatchedPair(Transaction a, Transaction b, BigDecimal amountDelta, int dateDeltaDays) {
    }

    public record MatchOutcome(List<MatchedPair> matchedPairs, List<Transaction> exceptionsA,
                                List<Transaction> exceptionsB) {
    }
}
