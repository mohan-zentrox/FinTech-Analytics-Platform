package com.zentrox.ledger.dto.reconciliation;

import com.zentrox.ledger.dto.transaction.TransactionDto;

import java.util.List;
import java.util.UUID;

public record ReconciliationResult(
        UUID runId,
        List<MatchedPairDto> matched,
        List<TransactionDto> exceptionsA,
        List<TransactionDto> exceptionsB,
        int matchedCount,
        int exceptionCount
) {
}
