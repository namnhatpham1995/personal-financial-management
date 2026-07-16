package com.fintrack.vault.service;

import com.fintrack.agent.domain.AgentRun;
import com.fintrack.agent.domain.AgentRunStatus;
import com.fintrack.agent.repository.AgentRunRepository;
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
    private final AgentRunRepository agentRunRepository;

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
        return toResponse(vaultDocumentRepository.save(doc), null);
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
        return toResponse(vaultDocumentRepository.save(doc), null);
    }

    public VaultDocumentResponse getById(Long userId, String id) {
        VaultDocument doc = findOwned(userId, id);
        return toResponse(doc, latestIngestionStatus(userId, doc.getId()));
    }

    public Page<VaultDocumentResponse> list(Long userId, Pageable pageable) {
        Page<VaultDocument> page = vaultDocumentRepository.findByUserIdOrderByCapturedAtDesc(userId, pageable);
        Map<String, AgentRunStatus> statuses = latestIngestionStatuses(userId, page.getContent());
        return page.map(doc -> toResponse(doc, statuses.get(doc.getId())));
    }

    public Page<VaultDocumentResponse> search(Long userId, VaultSearchRequest req, Pageable pageable) {
        Page<VaultDocument> page = vaultDocumentRepository.search(
                userId,
                req.merchant(),
                req.from(),
                req.to(),
                req.lineItemText(),
                req.maxLineItemAmount(),
                pageable
        );
        Map<String, AgentRunStatus> statuses = latestIngestionStatuses(userId, page.getContent());
        return page.map(doc -> toResponse(doc, statuses.get(doc.getId())));
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
        VaultDocument saved = vaultDocumentRepository.save(doc);
        return toResponse(saved, latestIngestionStatus(userId, saved.getId()));
    }

    /** Returns the list of vault document ids that are attached to the given transaction ids. */
    public List<VaultDocumentResponse> findByTransactionIds(Long userId, List<Long> transactionIds) {
        List<VaultDocument> docs = vaultDocumentRepository.findByTransactionIdInAndUserId(transactionIds, userId);
        Map<String, AgentRunStatus> statuses = latestIngestionStatuses(userId, docs);
        return docs.stream()
                .map(doc -> toResponse(doc, statuses.get(doc.getId())))
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

    /**
     * Latest ingestion run status per document, scoped to {@code userId} like all other vault
     * data — a run belonging to a different user can never surface here even if two users
     * somehow shared a vault document id (they can't, but the query is scoped defensively).
     */
    private Map<String, AgentRunStatus> latestIngestionStatuses(Long userId, List<VaultDocument> docs) {
        List<String> docIds = docs.stream().map(VaultDocument::getId).toList();
        if (docIds.isEmpty()) {
            return Map.of();
        }
        return agentRunRepository.findByVaultDocumentIdInAndUser_Id(docIds, userId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        AgentRun::getVaultDocumentId,
                        run -> run,
                        (a, b) -> a.getCreatedAt().isAfter(b.getCreatedAt()) ? a : b))
                .entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getStatus()));
    }

    private AgentRunStatus latestIngestionStatus(Long userId, String vaultDocumentId) {
        return agentRunRepository.findFirstByVaultDocumentIdAndUser_IdOrderByCreatedAtDesc(vaultDocumentId, userId)
                .map(AgentRun::getStatus)
                .orElse(null);
    }

    private VaultDocumentResponse toResponse(VaultDocument doc, AgentRunStatus ingestionStatus) {
        return new VaultDocumentResponse(
                doc.getId(),
                doc.getType(),
                doc.getStatus(),
                doc.getSource(),
                doc.getCapturedAt(),
                doc.getPayload(),
                doc.getGridFsFileId() != null,
                doc.getOriginalFilename(),
                doc.getTransactionId(),
                ingestionStatus
        );
    }
}
