package com.zentrox.ledger.repository;

import com.zentrox.ledger.entity.ReconciliationMatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReconciliationMatchRepository extends JpaRepository<ReconciliationMatch, UUID> {

    List<ReconciliationMatch> findByRunId(UUID runId);
}
