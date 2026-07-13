package com.zentrox.ledger.dto.reconciliation;

import com.zentrox.ledger.dto.transaction.TransactionDto;

import java.math.BigDecimal;

public record MatchedPairDto(
        TransactionDto transactionA,
        TransactionDto transactionB,
        BigDecimal amountDelta,
        int dateDeltaDays
) {
}
