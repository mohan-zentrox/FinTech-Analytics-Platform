package com.zentrox.ledger.repository.spec;

import com.zentrox.ledger.dto.transaction.TransactionSearchCriteria;
import com.zentrox.ledger.entity.Transaction;
import org.springframework.data.jpa.domain.Specification;

/** Builds a null-safe JPA Specification from TransactionSearchCriteria for GET /api/transactions. */
public final class TransactionSpecifications {

    private TransactionSpecifications() {
    }

    public static Specification<Transaction> fromCriteria(TransactionSearchCriteria criteria) {
        Specification<Transaction> spec = Specification.where(null);

        if (criteria.account() != null && !criteria.account().isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("account"), criteria.account()));
        }
        if (criteria.dateFrom() != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("postedDate"), criteria.dateFrom()));
        }
        if (criteria.dateTo() != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("postedDate"), criteria.dateTo()));
        }
        if (criteria.minAmount() != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("amount"), criteria.minAmount()));
        }
        if (criteria.maxAmount() != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("amount"), criteria.maxAmount()));
        }
        if (criteria.status() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), criteria.status()));
        }
        return spec;
    }
}
