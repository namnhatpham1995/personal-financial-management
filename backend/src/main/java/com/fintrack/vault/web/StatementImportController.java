package com.fintrack.vault.web;

import com.fintrack.common.security.UserPrincipal;
import com.fintrack.vault.service.StatementImportService;
import com.fintrack.vault.web.dto.ConfirmImportRequest;
import com.fintrack.vault.web.dto.StagedRowResponse;
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
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> upload(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam Long accountId,
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        String documentId = importService.upload(principal.getUserId(), accountId, file);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("documentId", documentId));
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
     * Confirm selected rows: inserts them as PostgreSQL transactions and activates the vault doc.
     * Duplicate rows (already imported) are silently skipped.
     * Returns how many new transactions were created.
     */
    @PostMapping("/{documentId}/confirm")
    public Map<String, Integer> confirm(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String documentId,
            @Valid @RequestBody ConfirmImportRequest request
    ) {
        int created = importService.confirm(principal.getUserId(), documentId, request);
        return Map.of("created", created);
    }
}
