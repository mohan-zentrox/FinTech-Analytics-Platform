package com.zentrox.ledger.repository;

import com.zentrox.ledger.entity.ReportRun;
import com.zentrox.ledger.entity.ReportType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface ReportRunRepository extends JpaRepository<ReportRun, UUID> {

    /**
     * Report history. Returns the {@link ReportRunSummary} projection rather than
     * entities so the stored documents are never loaded just to render a list.
     */
    Page<ReportRunSummary> findAllProjectedBy(Pageable pageable);

    Page<ReportRunSummary> findByReportType(ReportType reportType, Pageable pageable);

    /** Retention sweep run by the scheduled job; returns the number of rows removed. */
    @Modifying
    @Query("DELETE FROM ReportRun r WHERE r.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
