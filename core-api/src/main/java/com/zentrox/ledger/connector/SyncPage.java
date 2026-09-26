package com.zentrox.ledger.connector;

import java.util.List;

/**
 * One page of a provider's transaction feed.
 *
 * @param nextCursor opaque token for the following page, or null when this is
 *                   the last page. Kept opaque because the three providers
 *                   paginate differently (offset, page number, continuation
 *                   token) and the sync loop should not care which.
 */
public record SyncPage(
        List<ExternalTransaction> transactions,
        String nextCursor
) {
    public static SyncPage last(List<ExternalTransaction> transactions) {
        return new SyncPage(transactions, null);
    }
}
