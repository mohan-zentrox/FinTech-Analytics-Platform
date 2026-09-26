package com.zentrox.ledger.dto.connector;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Outcome of a connector sync.
 *
 * `skipped` is the de-duplication count: a second sync over the same window
 * reports every row skipped and none imported, which is the (source, externalId)
 * idempotency guarantee made visible.
 */
public record SyncResult(
        String provider,
        String account,
        LocalDate since,
        boolean sandbox,
        int pagesFetched,
        int fetched,
        int imported,
        int skipped,
        int errored,
        List<String> errors,
        Instant syncedAt
) {
}
