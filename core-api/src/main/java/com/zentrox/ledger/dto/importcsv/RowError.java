package com.zentrox.ledger.dto.importcsv;

/** row is 1-based and counts header as row 0 (i.e. first data row = 1). */
public record RowError(int row, String message) {
}
