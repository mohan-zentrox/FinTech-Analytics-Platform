package com.zentrox.ledger.connector;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A provider's transaction, already normalised into the fields the canonical
 * ledger needs (FRD S6.2).
 *
 * Every provider-specific response shape is mapped to this record by its
 * connector, so the ingestion path - and the (source, externalId) de-duplication
 * it relies on - is identical for CSV imports and every connector.
 */
public record ExternalTransaction(
        String externalId,
        String account,
        BigDecimal amount,
        String currency,
        LocalDate postedDate,
        String description,
        String category
) {
}
