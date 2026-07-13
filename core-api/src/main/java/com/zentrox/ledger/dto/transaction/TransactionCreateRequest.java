package com.zentrox.ledger.dto.transaction;

import com.zentrox.ledger.entity.TransactionStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TransactionCreateRequest(
        @NotBlank @Size(max = 50) String source,
        @NotBlank @Size(max = 100) String account,
        @NotNull BigDecimal amount,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @NotNull LocalDate postedDate,
        @Size(max = 500) String description,
        @Size(max = 100) String category,
        TransactionStatus status,
        @NotBlank @Size(max = 150) String externalId
) {
}
