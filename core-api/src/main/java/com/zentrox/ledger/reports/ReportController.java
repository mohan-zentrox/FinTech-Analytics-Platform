package com.zentrox.ledger.reports;

import com.zentrox.ledger.dto.common.PageResponse;
import com.zentrox.ledger.dto.reports.ReportGenerateRequest;
import com.zentrox.ledger.dto.reports.ReportRunDto;
import com.zentrox.ledger.entity.ReportRun;
import com.zentrox.ledger.entity.ReportType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * FRD S6.1 "Scheduled Reporting".
 *
 * Replaces the original scaffold (which reserved
 * POST /api/reports/cash-flow/generate and returned 501). That path is kept as a
 * deprecated alias so anything already coded against it keeps working, while the
 * generic POST /api/reports/generate covers all three report types.
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ReportGenerationService reportGenerationService;

    /** POST /api/reports/generate - render a report now and store it in report_run. */
    @PostMapping("/generate")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public ResponseEntity<ReportRunDto> generate(@Valid @RequestBody ReportGenerateRequest request,
                                                 Authentication authentication) {
        ReportRun run = reportGenerationService.generate(request, actorOf(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(ReportRunDto.from(run));
    }

    /**
     * Original scaffolded route, preserved as an alias for
     * POST /api/reports/generate with reportType=CASH_FLOW.
     *
     * @deprecated use {@code POST /api/reports/generate}.
     */
    @Deprecated(since = "0.2.0")
    @PostMapping("/cash-flow/generate")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public ResponseEntity<ReportRunDto> generateCashFlowReport(
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) com.zentrox.ledger.entity.ReportFormat format,
            Authentication authentication) {
        ReportRun run = reportGenerationService.generate(
                new ReportGenerateRequest(ReportType.CASH_FLOW, format, accountId, null, null),
                actorOf(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(ReportRunDto.from(run));
    }

    /** GET /api/reports?reportType=&page=&size= - report history, newest first. */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST','VIEWER')")
    public ResponseEntity<PageResponse<ReportRunDto>> history(
            @RequestParam(required = false) ReportType reportType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        var pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        var result = reportGenerationService.history(reportType, pageable).map(ReportRunDto::from);
        return ResponseEntity.ok(PageResponse.from(result));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST','VIEWER')")
    public ResponseEntity<ReportRunDto> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ReportRunDto.from(reportGenerationService.getRun(id)));
    }

    /** GET /api/reports/{id}/download - streams the stored PDF/XLSX document. */
    @GetMapping("/{id}/download")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST','VIEWER')")
    public ResponseEntity<Resource> download(@PathVariable UUID id) {
        ReportRun run = reportGenerationService.getDownloadable(id);

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(run.getFileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(run.getContentType()))
                .contentLength(run.getContent().length)
                .body(new ByteArrayResource(run.getContent()));
    }

    private String actorOf(Authentication authentication) {
        return authentication == null ? "system" : authentication.getName();
    }
}
