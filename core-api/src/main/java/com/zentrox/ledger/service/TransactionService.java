package com.zentrox.ledger.service;

import com.zentrox.ledger.aspect.Audited;
import com.zentrox.ledger.dto.transaction.TransactionCreateRequest;
import com.zentrox.ledger.dto.transaction.TransactionSearchCriteria;
import com.zentrox.ledger.dto.transaction.TransactionUpdateRequest;
import com.zentrox.ledger.entity.Transaction;
import com.zentrox.ledger.entity.TransactionStatus;
import com.zentrox.ledger.exception.DuplicateResourceException;
import com.zentrox.ledger.exception.ResourceNotFoundException;
import com.zentrox.ledger.repository.TransactionRepository;
import com.zentrox.ledger.repository.spec.TransactionSpecifications;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;

    @Transactional(readOnly = true)
    public Page<Transaction> search(TransactionSearchCriteria criteria, Pageable pageable) {
        return transactionRepository.findAll(TransactionSpecifications.fromCriteria(criteria), pageable);
    }

    @Transactional(readOnly = true)
    public Transaction getById(UUID id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + id));
    }

    @Audited(action = "CREATE", entity = "Transaction")
    @Transactional
    public Transaction create(TransactionCreateRequest request) {
        if (transactionRepository.existsBySourceAndExternalId(request.source(), request.externalId())) {
            throw new DuplicateResourceException(
                    "Transaction already exists for source=" + request.source() + " externalId=" + request.externalId());
        }
        Transaction tx = Transaction.builder()
                .source(request.source())
                .account(request.account())
                .amount(request.amount())
                .currency(request.currency())
                .postedDate(request.postedDate())
                .description(request.description())
                .category(request.category())
                .status(request.status() == null ? TransactionStatus.PENDING : request.status())
                .externalId(request.externalId())
                .build();
        return transactionRepository.save(tx);
    }

    @Audited(action = "UPDATE", entity = "Transaction")
    @Transactional
    public Transaction update(UUID id, TransactionUpdateRequest request) {
        Transaction tx = getById(id);
        tx.setAccount(request.account());
        tx.setAmount(request.amount());
        tx.setCurrency(request.currency());
        tx.setPostedDate(request.postedDate());
        tx.setDescription(request.description());
        tx.setCategory(request.category());
        tx.setStatus(request.status());
        return transactionRepository.save(tx);
    }

    @Audited(action = "DELETE", entity = "Transaction")
    @Transactional
    public void delete(UUID id) {
        if (!transactionRepository.existsById(id)) {
            throw new ResourceNotFoundException("Transaction not found: " + id);
        }
        transactionRepository.deleteById(id);
    }
}
