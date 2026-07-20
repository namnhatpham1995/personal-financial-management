package com.fintrack.vault.web;

import com.fintrack.common.security.UserPrincipal;
import com.fintrack.idempotency.exception.MissingIdempotencyKeyException;
import com.fintrack.vault.domain.VaultDocumentType;
import com.fintrack.vault.service.VaultService;
import com.fintrack.vault.web.dto.VaultDocumentResponse;
import com.fintrack.vault.web.dto.VaultSearchRequest;
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

    /** Upload a receipt image or statement file. Requires an {@code Idempotency-Key} header. */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VaultDocumentResponse> upload(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam VaultDocumentType type,
            @RequestParam("file") MultipartFile file,
            @Parameter(required = true, description = "Client-generated key (16-128 URL-safe characters) "
                    + "required for every vault upload; a retry with the same key and file replays the "
                    + "original document instead of storing a duplicate binary.")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) throws IOException {
        if (idempotencyKey == null) {
            throw new MissingIdempotencyKeyException("Idempotency-Key header is required for vault uploads");
        }
        var outcome = vaultService.upload(principal.getUserId(), type, file, idempotencyKey);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.CREATED);
        if (outcome.replayed()) {
            builder = builder.header("Idempotency-Replayed", "true");
        }
        return builder.body(outcome.response());
    }

    @GetMapping
    public Page<VaultDocumentResponse> list(
            @AuthenticationPrincipal UserPrincipal principal,
            Pageable pageable
    ) {
        return vaultService.list(principal.getUserId(), pageable);
    }

    @GetMapping("/{id}")
    public VaultDocumentResponse getById(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id
    ) {
        return vaultService.getById(principal.getUserId(), id);
    }

    /** Download the raw binary (image / CSV / OFX) stored in GridFS. */
    @GetMapping("/{id}/download")
    public ResponseEntity<org.springframework.core.io.Resource> download(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id
    ) throws IOException {
        GridFsResource resource = vaultService.download(principal.getUserId(), id);
        if (resource == null || !resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + resource.getFilename() + "\"")
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
