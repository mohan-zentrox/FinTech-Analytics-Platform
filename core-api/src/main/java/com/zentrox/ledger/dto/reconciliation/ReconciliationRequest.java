package com.zentrox.ledger.dto.reconciliation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Defines the two transaction sets to reconcile against each other, e.g.
 * "internal ledger" (setA: source=manual) vs "bank statement" (setB: source=csv-import),
 * for the same account and date range.
 */
public record ReconciliationRequest(
        @NotBlank String accountA,
        @NotBlank String sourceA,
        @NotBlank String accountB,
        @NotBlank String sourceB,
        @NotNull LocalDate dateFrom,
        @NotNull LocalDate dateTo,
        @NotNull @PositiveOrZero BigDecimal amountTolerance,
        @NotNull @PositiveOrZero Integer dateToleranceDays
) {
}
