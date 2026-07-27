package com.fintrack.vault.web;

import com.fintrack.common.security.UserPrincipal;
import com.fintrack.idempotency.exception.MissingIdempotencyKeyException;
import com.fintrack.vault.domain.VaultDocumentType;
import com.fintrack.vault.service.VaultService;
import com.fintrack.vault.web.dto.VaultDocumentResponse;
import com.fintrack.vault.web.dto.VaultReassignmentPreviewResponse;
import com.fintrack.vault.web.dto.VaultReassignmentRequest;
import com.fintrack.vault.web.dto.VaultReassignmentResponse;
import com.fintrack.vault.web.dto.VaultSearchRequest;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/vault")
@RequiredArgsConstructor
public class VaultController {

    private final VaultService vaultService;
    private final com.fintrack.vault.service.VaultReassignmentService vaultReassignmentService;

    /** Upload a receipt image or statement file. Requires an {@code Idempotency-Key} header. */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VaultDocumentResponse> upload(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam VaultDocumentType type,
            @RequestParam Long accountId,
            @RequestParam("file") MultipartFile file,
            @Parameter(required = true, description = "Client-generated key (16-128 URL-safe characters) "
                    + "required for every vault upload; a retry with the same key and file replays the "
                    + "original document instead of storing a duplicate binary.")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) throws IOException {
        if (idempotencyKey == null) {
            throw new MissingIdempotencyKeyException("Idempotency-Key header is required for vault uploads");
        }
        var outcome = vaultService.upload(principal.getUserId(), type, accountId, file, idempotencyKey);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.CREATED);
        if (outcome.replayed()) {
            builder = builder.header("Idempotency-Replayed", "true");
        }
        return builder.body(outcome.response());
    }

    @GetMapping
    public Page<VaultDocumentResponse> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) VaultDocumentType type,
            @RequestParam(required = false) Long accountId,
            Pageable pageable
    ) {
        if (type == null) {
            if (accountId != null) {
                throw new IllegalArgumentException("accountId requires a type filter");
            }
            return vaultService.list(principal.getUserId(), pageable);
        }
        return vaultService.listByType(principal.getUserId(), type, accountId, pageable);
    }

    /** Lists vault documents for one account and type — backs the per-account Statement/Receipt tabs. */
    @GetMapping("/by-account")
    public Page<VaultDocumentResponse> listByAccount(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam Long accountId,
            @RequestParam VaultDocumentType type,
            Pageable pageable
    ) {
        return vaultService.listByAccount(principal.getUserId(), accountId, type, pageable);
    }

    /** Lists legacy receipt uploads that still need an account assignment. */
    @GetMapping("/unassigned-receipts")
    public Page<VaultDocumentResponse> listUnassignedReceipts(
            @AuthenticationPrincipal UserPrincipal principal,
            Pageable pageable
    ) {
        return vaultService.listUnassignedReceipts(principal.getUserId(), pageable);
    }

    /**
     * Compatibility assignment route for legacy receipts. New clients may provide an
     * Idempotency-Key to use the full reassignment workflow; callers that omit it retain the
     * one-time legacy behavior for backward compatibility.
     */
    @PatchMapping("/{id}/account")
    public VaultDocumentResponse assignReceiptAccount(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id,
            @RequestParam Long accountId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        if (idempotencyKey != null) {
            return vaultReassignmentService.reassign(
                    principal.getUserId(), id,
                    new VaultReassignmentRequest(accountId), idempotencyKey).document();
        }
        return vaultService.assignReceiptAccount(principal.getUserId(), id, accountId);
    }

    @GetMapping("/{id}/reassignment-preview")
    public VaultReassignmentPreviewResponse reassignmentPreview(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id,
            @RequestParam Long targetAccountId
    ) {
        return vaultReassignmentService.preview(principal.getUserId(), id, targetAccountId);
    }

    @PostMapping("/{id}/reassign")
    public VaultReassignmentResponse reassign(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id,
            @Valid @RequestBody VaultReassignmentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        if (idempotencyKey == null) {
            throw new MissingIdempotencyKeyException("Idempotency-Key header is required for Vault reassignment");
        }
        return vaultReassignmentService.reassign(principal.getUserId(), id, request, idempotencyKey);
    }

    @GetMapping("/{id}")
    public VaultDocumentResponse getById(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id
    ) {
        return vaultService.getById(principal.getUserId(), id);
    }

    /**
     * Serve the raw binary (image / PDF / CSV / OFX) stored in GridFS. Defaults to a forced
     * download; pass {@code disposition=inline} to permit in-browser rendering instead (the
     * frontend's DocumentReader).
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<org.springframework.core.io.Resource> download(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id,
            @RequestParam(defaultValue = "attachment") String disposition
    ) throws IOException {
        GridFsResource resource = vaultService.download(principal.getUserId(), id);
        if (resource == null || !resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        String safeDisposition = "inline".equalsIgnoreCase(disposition) ? "inline" : "attachment";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        safeDisposition + "; filename=\"" + resource.getFilename() + "\"")
                .contentType(MediaType.parseMediaType(
                        resource.getContentType() != null
                                ? resource.getContentType()
                                : MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .contentLength(resource.contentLength())
                .body(resource);
    }

    @PostMapping("/search")
    public Page<VaultDocumentResponse> search(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody VaultSearchRequest request,
            Pageable pageable
    ) {
        return vaultService.search(principal.getUserId(), request, pageable);
    }

    /** Link a vault document to a PostgreSQL transaction id. */
    @PatchMapping("/{id}/link")
    public VaultDocumentResponse linkToTransaction(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id,
            @RequestParam Long transactionId
    ) {
        return vaultService.linkToTransaction(principal.getUserId(), id, transactionId);
    }

    /** Batch-fetch vault docs attached to a list of transaction ids (used by transaction list). */
    @PostMapping("/by-transactions")
    public List<VaultDocumentResponse> byTransactions(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody List<Long> transactionIds
    ) {
        return vaultService.findByTransactionIds(principal.getUserId(), transactionIds);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id
    ) {
        vaultService.delete(principal.getUserId(), id);
    }
}
