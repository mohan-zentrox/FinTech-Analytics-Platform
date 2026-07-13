package com.zentrox.ledger.dto.transaction;

import com.zentrox.ledger.entity.TransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Parsed query params for GET /api/transactions. All fields optional. */
public record TransactionSearchCriteria(
        String account,
        LocalDate dateFrom,
        LocalDate dateTo,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        TransactionStatus status
) {
}
