package com.zentrox.ledger.repository;

import com.zentrox.ledger.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

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
}
