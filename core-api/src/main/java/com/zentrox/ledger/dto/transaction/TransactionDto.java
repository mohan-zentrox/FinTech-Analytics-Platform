package com.zentrox.ledger.dto.transaction;

import com.zentrox.ledger.entity.Transaction;
import com.zentrox.ledger.entity.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TransactionDto(
        UUID id,
        String source,
        String account,
        BigDecimal amount,
        String currency,
        LocalDate postedDate,
        String description,
        String category,
        TransactionStatus status,
        String externalId,
        Instant createdAt,
        Instant updatedAt
) {
    public static TransactionDto from(Transaction t) {
        return new TransactionDto(
                t.getId(), t.getSource(), t.getAccount(), t.getAmount(), t.getCurrency(),
                t.getPostedDate(), t.getDescription(), t.getCategory(), t.getStatus(),
                t.getExternalId(), t.getCreatedAt(), t.getUpdatedAt()
        );
    }
}
