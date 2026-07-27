package com.fintrack.vault.service;

import com.fintrack.agent.domain.AgentRun;
import com.fintrack.agent.domain.AgentRunStatus;
import com.fintrack.agent.repository.AgentRunRepository;
import com.fintrack.account.service.AccountService;
import com.fintrack.common.exception.ConflictException;
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
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class VaultService {

    private static final String OPERATION_NAME = "vault.upload";

    private final VaultDocumentRepository vaultDocumentRepository;
    private final GridFsFileStore gridFsFileStore;
    private final AgentRunRepository agentRunRepository;
    private final VaultUploadIdempotencyCoordinator idempotencyCoordinator;
    private final VaultAccountIdBackfillService accountIdBackfillService;
    private final AccountService accountService;
    private final MongoTemplate mongoTemplate;

    /**
     * Upload a binary file (receipt image or statement file) and create a VaultDocument in the
     * {@code UPLOADED} state — no parsing or ingestion happens as part of upload. Requires an
     * {@code Idempotency-Key} (per the document-vault spec, uploads SHALL require one) —
     * same-key/same-file retries replay the original document without a second binary;
     * same-key/different-file retries return a typed 409.
     */
    public VaultUploadOutcome<VaultDocumentResponse> upload(Long userId, VaultDocumentType type, Long accountId,
                                                              MultipartFile file, String rawIdempotencyKey) throws IOException {
        Map<String, String> nonFileParams = Map.of("type", type.name(), "accountId", String.valueOf(accountId));
        byte[] fileBytes = file.getBytes();

        return idempotencyCoordinator.execute(
                userId,
                OPERATION_NAME,
                rawIdempotencyKey,
                nonFileParams,
                fileBytes,
                operationId -> gridFsFileStore.store(file, userId, operationId),
                gridFsFileId -> {
                    VaultDocument doc = VaultDocument.builder()
                            .userId(userId)
                            .accountId(accountId)
                            .type(type)
                            .status(VaultDocumentStatus.UPLOADED)
                            .source("manual")
                            .capturedAt(Instant.now())
                            .gridFsFileId(gridFsFileId)
                            .originalFilename(file.getOriginalFilename())
                            .build();
                    VaultDocument saved = vaultDocumentRepository.save(doc);
                    return new VaultUploadResult<>(saved.getId(), toResponse(saved, null));
                },
                vaultDocumentId -> toResponse(findOwned(userId, vaultDocumentId), latestIngestionStatus(userId, vaultDocumentId)));
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

    /** Lists one document type overall or narrowed to one owned account. */
    public Page<VaultDocumentResponse> listByType(Long userId, VaultDocumentType type, Long accountId,
                                                   Pageable pageable) {
        accountIdBackfillService.ensureBackfillOnce();
        if (accountId != null) {
            accountService.findOwned(userId, accountId);
        }
        Page<VaultDocument> page = accountId == null
                ? vaultDocumentRepository.findByUserIdAndTypeOrderByCapturedAtDesc(userId, type, pageable)
                : vaultDocumentRepository.findByUserIdAndTypeAndAccountIdOrderByCapturedAtDesc(
                        userId, type, accountId, pageable);
        Map<String, AgentRunStatus> statuses = latestIngestionStatuses(userId, page.getContent());
        return page.map(doc -> toResponse(doc, statuses.get(doc.getId())));
    }

    /** Lists a user's vault documents for one account and type — backs the per-account Statement/Receipt tabs. */
    public Page<VaultDocumentResponse> listByAccount(Long userId, Long accountId, VaultDocumentType type, Pageable pageable) {
        accountIdBackfillService.ensureBackfillOnce();
        Page<VaultDocument> page = vaultDocumentRepository
                .findByUserIdAndAccountIdAndTypeOrderByCapturedAtDesc(userId, accountId, type, pageable);
        Map<String, AgentRunStatus> statuses = latestIngestionStatuses(userId, page.getContent());
        return page.map(doc -> toResponse(doc, statuses.get(doc.getId())));
    }

    /** Lists legacy receipts that predate required account assignment. */
    public Page<VaultDocumentResponse> listUnassignedReceipts(Long userId, Pageable pageable) {
        Page<VaultDocument> page = vaultDocumentRepository
                .findByUserIdAndTypeAndAccountIdIsNullOrderByCapturedAtDesc(
                        userId, VaultDocumentType.RECEIPT, pageable);
        Map<String, AgentRunStatus> statuses = latestIngestionStatuses(userId, page.getContent());
        return page.map(doc -> toResponse(doc, statuses.get(doc.getId())));
    }

    /**
     * Assigns an owner-selected account to a legacy receipt exactly once. The conditional Mongo
     * update prevents two concurrent recovery requests from moving the same financial document
     * to different accounts.
     */
    public VaultDocumentResponse assignReceiptAccount(Long userId, String id, Long accountId) {
        accountService.findOwned(userId, accountId);

        VaultDocument current = findOwned(userId, id);
        assertNotReassigning(current);
        if (current.getType() != VaultDocumentType.RECEIPT) {
            throw new ConflictException("Only receipt documents can be assigned through receipt recovery");
        }
        if (accountId.equals(current.getAccountId())) {
            return toResponse(current, latestIngestionStatus(userId, id));
        }
        if (current.getAccountId() != null) {
            throw new ConflictException("Receipt is already assigned to another account");
        }

        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(id),
                Criteria.where("userId").is(userId),
                Criteria.where("type").is(VaultDocumentType.RECEIPT),
                new Criteria().orOperator(
                        Criteria.where("accountId").exists(false),
                        Criteria.where("accountId").is(null))));
        VaultDocument updated = mongoTemplate.findAndModify(
                query,
                Update.update("accountId", accountId),
                FindAndModifyOptions.options().returnNew(true),
                VaultDocument.class);
        if (updated == null) {
            VaultDocument concurrentlyUpdated = findOwned(userId, id);
            if (accountId.equals(concurrentlyUpdated.getAccountId())) {
                return toResponse(concurrentlyUpdated, latestIngestionStatus(userId, id));
            }
            throw new ConflictException("Receipt was assigned to another account");
        }
        return toResponse(updated, latestIngestionStatus(userId, id));
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
        assertNotReassigning(doc);
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
        assertNotReassigning(doc);
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

    void assertNotReassigning(VaultDocument doc) {
        if (doc.getReassignmentOperationId() != null) {
            throw new com.fintrack.common.exception.ConflictException(
                    "Vault document is being reassigned; retry after the operation completes");
        }
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
                doc.getAccountId(),
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
