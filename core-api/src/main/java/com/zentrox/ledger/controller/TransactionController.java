package com.zentrox.ledger.controller;

import com.zentrox.ledger.dto.common.PageResponse;
import com.zentrox.ledger.dto.importcsv.CsvImportResult;
import com.zentrox.ledger.dto.transaction.TransactionCreateRequest;
import com.zentrox.ledger.dto.transaction.TransactionDto;
import com.zentrox.ledger.dto.transaction.TransactionSearchCriteria;
import com.zentrox.ledger.dto.transaction.TransactionUpdateRequest;
import com.zentrox.ledger.entity.TransactionStatus;
import com.zentrox.ledger.service.CsvImportService;
import com.zentrox.ledger.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    /**
     * Upper bound on `size`, mirroring AuditLogController and
     * FraudDetectionController. Without it `?size=100000000` is passed straight
     * through to `LIMIT`, and one request can pull the whole table into memory.
     */
    private static final int MAX_PAGE_SIZE = 200;

    private final TransactionService transactionService;
    private final CsvImportService csvImportService;

    /** GET /api/transactions?account=&dateFrom=&dateTo=&minAmount=&maxAmount=&status=&page=&size= */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST','VIEWER')")
    public ResponseEntity<PageResponse<TransactionDto>> search(
            @RequestParam(required = false) String account,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        TransactionSearchCriteria criteria = new TransactionSearchCriteria(
                account, dateFrom, dateTo, minAmount, maxAmount, status);
        Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "postedDate"));
        Page<TransactionDto> result = transactionService.search(criteria, pageable).map(TransactionDto::from);
        return ResponseEntity.ok(PageResponse.from(result));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST','VIEWER')")
    public ResponseEntity<TransactionDto> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(TransactionDto.from(transactionService.getById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public ResponseEntity<TransactionDto> create(@Valid @RequestBody TransactionCreateRequest request) {
        TransactionDto dto = TransactionDto.from(transactionService.create(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public ResponseEntity<TransactionDto> update(@PathVariable UUID id,
                                                  @Valid @RequestBody TransactionUpdateRequest request) {
        return ResponseEntity.ok(TransactionDto.from(transactionService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        transactionService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** POST /api/transactions/import - multipart CSV upload, see CsvImportService for schema. */
    @PostMapping(value = "/import", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public ResponseEntity<CsvImportResult> importCsv(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(csvImportService.importCsv(file));
    }
}
