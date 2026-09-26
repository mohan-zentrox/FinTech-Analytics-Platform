package com.zentrox.ledger.repository;

import com.zentrox.ledger.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID>,
        JpaSpecificationExecutor<Transaction> {

    boolean existsBySourceAndExternalId(String source, String externalId);

    Optional<Transaction> findBySourceAndExternalId(String source, String externalId);

    List<Transaction> findByAccountAndSourceAndPostedDateBetween(
            String account, String source, LocalDate from, LocalDate to);

    List<Transaction> findByAccountAndPostedDateBetween(String account, LocalDate from, LocalDate to);

    /** Every account's activity in a window - the default scope of a fraud scan. */
    List<Transaction> findByPostedDateBetween(LocalDate from, LocalDate to);

    /** Distinct accounts with activity in a window, used by the scheduled report job. */
    @Query("SELECT DISTINCT t.account FROM Transaction t WHERE t.postedDate BETWEEN :from AND :to ORDER BY t.account")
    List<String> findDistinctAccountsBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
