package com.zentrox.ledger.repository;

import com.zentrox.ledger.entity.MatchType;
import com.zentrox.ledger.entity.ReconciliationMatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReconciliationMatchRepository extends JpaRepository<ReconciliationMatch, UUID> {

    List<ReconciliationMatch> findByRunId(UUID runId);

    /** Backs the FRD S6.1 reconciliation exception report. */
    List<ReconciliationMatch> findByMatchTypeAndCreatedAtBetween(
            MatchType matchType, Instant from, Instant to);
}
