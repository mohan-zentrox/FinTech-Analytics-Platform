package com.zentrox.ledger.dto.importcsv;

import java.util.List;

public record CsvImportResult(
        int imported,
        int skipped,
        int errored,
        List<RowError> errors
) {
}
