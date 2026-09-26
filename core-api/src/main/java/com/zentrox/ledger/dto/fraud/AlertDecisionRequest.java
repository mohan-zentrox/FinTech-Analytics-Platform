package com.zentrox.ledger.dto.fraud;

import com.zentrox.ledger.entity.AlertStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** PATCH /api/fraud/alerts/{id} - an analyst's triage decision on one alert. */
public record AlertDecisionRequest(
        @NotNull AlertStatus status,
        @Size(max = 500) String resolutionNote
) {
}
