package com.fintrack.vault.web;

import com.fintrack.common.security.UserPrincipal;
import com.fintrack.idempotency.exception.MissingIdempotencyKeyException;
import com.fintrack.vault.service.StatementImportService;
import com.fintrack.vault.web.dto.ConfirmImportRequest;
import com.fintrack.vault.web.dto.ConfirmImportResponse;
import com.fintrack.vault.web.dto.StagedRowResponse;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/vault/import")
@RequiredArgsConstructor
public class StatementImportController {

    private final StatementImportService importService;

    /**
     * Upload a CSV or OFX statement file.
     * Parses it, stores the binary in GridFS, and creates a STAGED vault document.
     * Returns the vault document id so the client can fetch rows for review.
     * Requires an {@code Idempotency-Key} header.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> upload(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam Long accountId,
            @RequestParam("file") MultipartFile file,
            @Parameter(required = true, description = "Client-generated key (16-128 URL-safe characters) "
                    + "required for every statement upload; a retry with the same key, account, and file "
                    + "replays the original staged document instead of storing a duplicate binary.")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) throws IOException {
        if (idempotencyKey == null) {
            throw new MissingIdempotencyKeyException("Idempotency-Key header is required for statement uploads");
        }
        var outcome = importService.upload(principal.getUserId(), accountId, file, idempotencyKey);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.CREATED);
        if (outcome.replayed()) {
            builder = builder.header("Idempotency-Replayed", "true");
        }
        return builder.body(Map.of("documentId", outcome.response()));
    }

    /** Returns staged rows parsed from the uploaded file, ready for user review. */
    @GetMapping("/{documentId}/rows")
    public List<StagedRowResponse> getReviewRows(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String documentId
    ) {
        return importService.getReviewRows(principal.getUserId(), documentId);
    }

    /**
     * Confirm selected rows: normalizes them into PostgreSQL transactions and activates the vault
     * document. Requires an {@code Idempotency-Key}; a same-key/same-selection retry resumes or
     * replays the durable per-row result instead of 404ing or duplicating transactions, and a
     * same-key/different-selection retry returns a typed 409.
     */
    @PostMapping("/{documentId}/confirm")
    public ConfirmImportResponse confirm(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String documentId,
            @Valid @RequestBody ConfirmImportRequest request,
            @Parameter(required = true, description = "Client-generated key (16-128 URL-safe characters) "
                    + "required for every statement confirmation; a retry with the same key and the same "
                    + "selected-row set resumes or replays the durable per-row result instead of 404ing or "
                    + "creating duplicate transactions. A different selected-row set under the same key returns 409.")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        if (idempotencyKey == null) {
            throw new MissingIdempotencyKeyException("Idempotency-Key header is required for statement confirmation");
        }
        return importService.confirm(principal.getUserId(), documentId, request, idempotencyKey);
    }
}
