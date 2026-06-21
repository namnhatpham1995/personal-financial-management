package com.fintrack.vault.service;

import com.fintrack.common.exception.ResourceNotFoundException;
import com.fintrack.vault.domain.VaultDocument;
import com.fintrack.vault.domain.VaultDocumentStatus;
import com.fintrack.vault.domain.VaultDocumentType;
import com.fintrack.vault.repository.VaultDocumentRepository;
import com.fintrack.vault.web.dto.VaultDocumentResponse;
import com.fintrack.vault.web.dto.VaultSearchRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class VaultService {

    private final VaultDocumentRepository vaultDocumentRepository;
    private final GridFsFileStore gridFsFileStore;

    /** Upload a binary file (receipt image or statement file) and create a VaultDocument. */
    public VaultDocumentResponse upload(Long userId, VaultDocumentType type, MultipartFile file)
            throws IOException {
        String fileId = gridFsFileStore.store(file, userId);
        VaultDocument doc = VaultDocument.builder()
                .userId(userId)
                .type(type)
                .status(VaultDocumentStatus.ACTIVE)
                .source("manual")
                .capturedAt(Instant.now())
                .gridFsFileId(fileId)
                .originalFilename(file.getOriginalFilename())
                .build();
        return toResponse(vaultDocumentRepository.save(doc));
    }

    /** Create a VaultDocument from structured data (no binary), e.g. a manual receipt. */
    public VaultDocumentResponse create(Long userId, VaultDocumentType type, String source,
                                        Map<String, Object> payload) {
        VaultDocument doc = VaultDocument.builder()
                .userId(userId)
                .type(type)
                .status(VaultDocumentStatus.ACTIVE)
                .source(source)
                .capturedAt(Instant.now())
                .payload(payload)
                .build();
        return toResponse(vaultDocumentRepository.save(doc));
    }

    public VaultDocumentResponse getById(Long userId, String id) {
        return toResponse(findOwned(userId, id));
    }

    public Page<VaultDocumentResponse> list(Long userId, Pageable pageable) {
        return vaultDocumentRepository
                .findByUserIdOrderByCapturedAtDesc(userId, pageable)
                .map(this::toResponse);
    }

    public Page<VaultDocumentResponse> search(Long userId, VaultSearchRequest req, Pageable pageable) {
        return vaultDocumentRepository.search(
                userId,
                req.merchant(),
                req.from(),
                req.to(),
                req.lineItemText(),
                req.maxLineItemAmount(),
                pageable
        ).map(this::toResponse);
    }

    /** Download the raw binary for a vault document. Returns null if no binary stored. */
    public GridFsResource download(Long userId, String id) {
        VaultDocument doc = findOwned(userId, id);
        if (doc.getGridFsFileId() == null) {
            return null;
        }
        return gridFsFileStore.load(doc.getGridFsFileId(), userId);
    }

    /** Link an existing vault document to a PostgreSQL transaction. */
    public VaultDocumentResponse linkToTransaction(Long userId, String id, Long transactionId) {
        VaultDocument doc = findOwned(userId, id);
        doc.setTransactionId(transactionId);
        return toResponse(vaultDocumentRepository.save(doc));
    }

    /** Returns the list of vault document ids that are attached to the given transaction ids. */
    public List<VaultDocumentResponse> findByTransactionIds(Long userId, List<Long> transactionIds) {
        return vaultDocumentRepository
                .findByTransactionIdInAndUserId(transactionIds, userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public void delete(Long userId, String id) {
        VaultDocument doc = findOwned(userId, id);
        if (doc.getGridFsFileId() != null) {
            gridFsFileStore.delete(doc.getGridFsFileId());
        }
        vaultDocumentRepository.deleteById(doc.getId());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    VaultDocument findOwned(Long userId, String id) {
        return vaultDocumentRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ResourceNotFoundException.of("VaultDocument", id));
    }

    private VaultDocumentResponse toResponse(VaultDocument doc) {
        return new VaultDocumentResponse(
                doc.getId(),
                doc.getType(),
                doc.getStatus(),
                doc.getSource(),
                doc.getCapturedAt(),
                doc.getPayload(),
                doc.getGridFsFileId() != null,
                doc.getOriginalFilename(),
                doc.getTransactionId()
        );
    }
}
