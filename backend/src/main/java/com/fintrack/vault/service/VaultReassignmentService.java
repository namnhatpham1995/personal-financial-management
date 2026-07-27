package com.fintrack.vault.service;

import com.fintrack.account.domain.Account;
import com.fintrack.account.service.AccountService;
import com.fintrack.audit.support.AuditReplaySignal;
import com.fintrack.common.exception.ConflictException;
import com.fintrack.common.exception.ResourceNotFoundException;
import com.fintrack.idempotency.exception.IdempotencyConflictException;
import com.fintrack.idempotency.exception.IdempotencyOperationInProgressException;
import com.fintrack.idempotency.service.IdempotencyHasher;
import com.fintrack.idempotency.service.IdempotencyKeyValidator;
import com.fintrack.transaction.domain.Transaction;
import com.fintrack.transaction.repository.TransactionRepository;
import com.fintrack.vault.domain.VaultDocument;
import com.fintrack.vault.domain.VaultDocumentStatus;
import com.fintrack.vault.domain.VaultReassignmentOperation;
import com.fintrack.vault.domain.VaultReassignmentState;
import com.fintrack.vault.repository.VaultDocumentRepository;
import com.fintrack.vault.repository.VaultReassignmentOperationRepository;
import com.fintrack.vault.web.dto.VaultReassignmentPreviewResponse;
import com.fintrack.vault.web.dto.VaultReassignmentRequest;
import com.fintrack.vault.web.dto.VaultReassignmentResponse;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class VaultReassignmentService {

    private static final String OPERATION_NAME = "vault.reassign";
    private static final Duration RESULT_RETENTION = Duration.ofDays(7);

    private final VaultDocumentRepository vaultDocumentRepository;
    private final VaultReassignmentOperationRepository operationRepository;
    private final VaultImportOriginService importOriginService;
    private final VaultReassignmentCleanupService cleanupService;
    private final VaultService vaultService;
    private final AccountService accountService;
    private final TransactionRepository transactionRepository;
    private final IdempotencyKeyValidator keyValidator;
    private final IdempotencyHasher hasher;
    private final MongoTemplate mongoTemplate;
    private final AuditReplaySignal auditReplaySignal;
    private final VaultOperationMetrics metrics;

    private final AtomicBoolean indexesEnsured = new AtomicBoolean(false);

    public VaultReassignmentPreviewResponse preview(Long userId, String documentId, Long targetAccountId) {
        VaultDocument document = findOwned(userId, documentId);
        Account target = accountService.findOwned(userId, targetAccountId);
        Account source = sourceAccount(userId, document);
        ensureDifferentAccount(document, targetAccountId);
        Set<Long> importedIds = importOriginService.collectTransactionIds(userId, document);
        return toPreview(document, source, target, importedIds.size(), manualLinkMustDetach(userId, document, targetAccountId));
    }

    public VaultReassignmentResponse reassign(Long userId, String documentId,
                                              VaultReassignmentRequest request, String rawIdempotencyKey) {
        keyValidator.validate(rawIdempotencyKey);
        ensureIndexesOnce();
        String keyHash = hasher.hashKey(rawIdempotencyKey);
        String requestHash = hasher.hashJsonRequest(OPERATION_NAME,
                Map.of("documentId", documentId, "targetAccountId", request.targetAccountId()));
        VaultReassignmentOperation operation = claimOperation(userId, documentId,
                request.targetAccountId(), keyHash, requestHash);

        if (operation.getState() == VaultReassignmentState.COMPLETED) {
            metrics.replayed(OPERATION_NAME);
            auditReplaySignal.markReplayed();
            return replay(operation, userId);
        }

        VaultDocument document;
        Account target;
        try {
            document = findOwned(userId, documentId);
            target = accountService.findOwned(userId, request.targetAccountId());
            sourceAccount(userId, document);
            ensureDifferentAccount(document, target.getId());
            if (document.getStatus() == VaultDocumentStatus.CONFIRMING) {
                throw new ConflictException("Statement confirmation is still processing; retry after it completes");
            }
        } catch (RuntimeException e) {
            failOperation(operation, e.getMessage());
            throw e;
        }

        VaultDocument claimed = claimDocument(document, operation.getId());
        if (claimed == null) {
            failOperation(operation, "Vault document is already being reassigned");
            throw new ConflictException("Vault document is already being reassigned");
        }

        Set<Long> importedIds = importOriginService.collectTransactionIds(userId, claimed);
        operation.setSourceAccountId(claimed.getAccountId());
        operation.setRemovedTransactionIds(new ArrayList<>(importedIds));
        operation.setRemovedTransactionCount(importedIds.size());
        operation.setManualLinkDetached(manualLinkMustDetach(userId, claimed, target.getId()));
        operationRepository.save(operation);

        try {
            VaultReassignmentCleanupService.CleanupResult cleanup = cleanupService.clean(
                    userId, documentId, claimed.getAccountId(), importedIds,
                    "Vault document reassigned to another account");
            operation.setRemovedTransactionCount(cleanup.removedTransactions());
            operationRepository.save(operation);
            VaultDocument finalized = finalizeDocument(userId, claimed, operation.getId(), target.getId());
            operation.setState(VaultReassignmentState.COMPLETED);
            operation.setCompletedAt(Instant.now());
            operationRepository.save(operation);
            auditReplaySignal.setOperationReference(operation.getId());
            metrics.reassignmentCompleted();
            return new VaultReassignmentResponse(
                    vaultService.getById(userId, finalized.getId()),
                    operation.getRemovedTransactionCount() == null ? 0 : operation.getRemovedTransactionCount(),
                    Boolean.TRUE.equals(operation.getManualLinkDetached()), false);
        } catch (RuntimeException e) {
            metrics.reassignmentCleanupFailed();
            operation.setState(VaultReassignmentState.FAILED);
            operation.setFailureReason(e.getMessage());
            operationRepository.save(operation);
            clearClaim(userId, documentId, operation.getId());
            throw e;
        }
    }

    /** Resumes a stale operation after a process interruption; called by the bounded scheduler. */
    public void recoverStale(VaultReassignmentOperation operation) {
        if (operation.getState() != VaultReassignmentState.PROCESSING) {
            return;
        }
        VaultDocument document;
        try {
            document = findOwned(operation.getUserId(), operation.getDocumentId());
            if (operation.getTargetAccountId().equals(document.getAccountId())
                    && document.getReassignmentOperationId() == null) {
                operation.setState(VaultReassignmentState.COMPLETED);
                operation.setCompletedAt(Instant.now());
                operationRepository.save(operation);
                metrics.reassignmentRecovered();
                return;
            }
            Account target = accountService.findOwned(operation.getUserId(), operation.getTargetAccountId());
            if (document.getStatus() == VaultDocumentStatus.CONFIRMING) {
                failOperation(operation, "Statement confirmation is still processing");
                clearClaim(operation.getUserId(), operation.getDocumentId(), operation.getId());
                return;
            }
            VaultDocument claimed = document.getReassignmentOperationId() == null
                    ? claimDocument(document, operation.getId()) : document;
            if (claimed == null) {
                return;
            }
            Set<Long> ids = operation.getRemovedTransactionIds() == null
                    ? importOriginService.collectTransactionIds(operation.getUserId(), claimed)
                    : Set.copyOf(operation.getRemovedTransactionIds());
            VaultReassignmentCleanupService.CleanupResult cleanup = cleanupService.clean(
                    operation.getUserId(), operation.getDocumentId(), operation.getSourceAccountId(), ids,
                    "Vault document reassigned to another account");
            operation.setRemovedTransactionCount(cleanup.removedTransactions());
            finalizeDocument(operation.getUserId(), claimed, operation.getId(), target.getId());
            operation.setState(VaultReassignmentState.COMPLETED);
            operation.setCompletedAt(Instant.now());
            operationRepository.save(operation);
            metrics.reassignmentRecovered();
        } catch (RuntimeException e) {
            operation.setState(VaultReassignmentState.FAILED);
            operation.setFailureReason(e.getMessage());
            operationRepository.save(operation);
            clearClaim(operation.getUserId(), operation.getDocumentId(), operation.getId());
        }
    }

    private VaultReassignmentResponse replay(VaultReassignmentOperation operation, Long userId) {
        return new VaultReassignmentResponse(
                vaultService.getById(userId, operation.getDocumentId()),
                operation.getRemovedTransactionCount() == null ? 0 : operation.getRemovedTransactionCount(),
                Boolean.TRUE.equals(operation.getManualLinkDetached()), true);
    }

    private VaultReassignmentOperation claimOperation(Long userId, String documentId, Long targetAccountId,
                                                      String keyHash, String requestHash) {
        Instant now = Instant.now();
        VaultReassignmentOperation candidate = VaultReassignmentOperation.builder()
                .userId(userId)
                .operation(OPERATION_NAME)
                .keyHash(keyHash)
                .requestHash(requestHash)
                .documentId(documentId)
                .targetAccountId(targetAccountId)
                .state(VaultReassignmentState.PROCESSING)
                .createdAt(now)
                .expiresAt(now.plus(RESULT_RETENTION))
                .build();
        try {
            return operationRepository.insert(candidate);
        } catch (DuplicateKeyException e) {
            VaultReassignmentOperation existing = operationRepository
                    .findByUserIdAndOperationAndKeyHash(userId, OPERATION_NAME, keyHash)
                    .orElseThrow(() -> new IllegalStateException("Reassignment operation disappeared after claim conflict"));
            if (!requestHash.equals(existing.getRequestHash())) {
                throw new IdempotencyConflictException(
                        "Idempotency-Key was already used for a different Vault reassignment");
            }
            if (existing.getState() == VaultReassignmentState.COMPLETED) {
                return existing;
            }
            if (existing.getState() == VaultReassignmentState.FAILED) {
                existing.setState(VaultReassignmentState.PROCESSING);
                existing.setCreatedAt(now);
                existing.setCompletedAt(null);
                existing.setFailureReason(null);
                return operationRepository.save(existing);
            }
            throw new IdempotencyOperationInProgressException(
                    "Another request with this Idempotency-Key is still reassigning the document", 1);
        }
    }

    private VaultDocument claimDocument(VaultDocument document, String operationId) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(document.getId()),
                Criteria.where("userId").is(document.getUserId()),
                new Criteria().orOperator(
                        Criteria.where("reassignmentOperationId").exists(false),
                        Criteria.where("reassignmentOperationId").is(null))));
        Update update = new Update()
                .set("reassignmentOperationId", operationId)
                .set("reassignmentStartedAt", Instant.now());
        return mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), VaultDocument.class);
    }

    private VaultDocument finalizeDocument(Long userId, VaultDocument document, String operationId, Long targetAccountId) {
        VaultDocument current = findOwned(userId, document.getId());
        if (!operationId.equals(current.getReassignmentOperationId())) {
            throw new ConflictException("Vault reassignment claim was lost");
        }
        if (current.getTransactionId() != null && manualLinkMustDetach(userId, current, targetAccountId)) {
            current.setTransactionId(null);
        }
        if (current.getType() == com.fintrack.vault.domain.VaultDocumentType.STATEMENT) {
            current.setPayload(null);
            current.setStatus(VaultDocumentStatus.UPLOADED);
            current.setConfirmationKeyHash(null);
            current.setConfirmationRequestHash(null);
            current.setConfirmationStartedAt(null);
            current.setConfirmationCompletedAt(null);
            current.setConfirmationRowOutcomes(null);
        }
        current.setAccountId(targetAccountId);
        current.setReassignmentOperationId(null);
        current.setReassignmentStartedAt(null);
        return vaultDocumentRepository.save(current);
    }

    private boolean manualLinkMustDetach(Long userId, VaultDocument document, Long targetAccountId) {
        if (document.getTransactionId() == null) {
            return false;
        }
        return transactionRepository.findByIdAndUserId(document.getTransactionId(), userId)
                .map(Transaction::getAccount)
                .map(account -> !targetAccountId.equals(account.getId()))
                .orElse(true);
    }

    private Account sourceAccount(Long userId, VaultDocument document) {
        return document.getAccountId() == null ? null : accountService.findOwned(userId, document.getAccountId());
    }

    private VaultReassignmentPreviewResponse toPreview(VaultDocument document, Account source, Account target,
                                                        int importedCount, boolean detachManualLink) {
        String sourceCurrency = source == null ? null : source.getCurrency();
        String targetCurrency = target.getCurrency();
        return new VaultReassignmentPreviewResponse(document.getId(), document.getType(),
                document.getOriginalFilename(), source == null ? null : source.getId(),
                source == null ? null : source.getName(), sourceCurrency, target.getId(), target.getName(),
                targetCurrency, sourceCurrency != null && !sourceCurrency.equals(targetCurrency),
                importedCount, detachManualLink);
    }

    private void ensureDifferentAccount(VaultDocument document, Long targetAccountId) {
        if (targetAccountId.equals(document.getAccountId())) {
            throw new ConflictException("Vault document is already assigned to this account");
        }
    }

    private VaultDocument findOwned(Long userId, String documentId) {
        return vaultDocumentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> ResourceNotFoundException.of("VaultDocument", documentId));
    }

    private void clearClaim(Long userId, String documentId, String operationId) {
        Query query = Query.query(Criteria.where("_id").is(documentId)
                .and("userId").is(userId)
                .and("reassignmentOperationId").is(operationId));
        mongoTemplate.updateFirst(query, new Update()
                .set("reassignmentOperationId", null)
                .set("reassignmentStartedAt", null), VaultDocument.class);
    }

    private void failOperation(VaultReassignmentOperation operation, String reason) {
        operation.setState(VaultReassignmentState.FAILED);
        operation.setFailureReason(reason);
        operationRepository.save(operation);
    }

    private void ensureIndexesOnce() {
        if (!indexesEnsured.compareAndSet(false, true)) {
            return;
        }
        var indexOps = mongoTemplate.indexOps(VaultReassignmentOperation.class);
        indexOps.ensureIndex(new CompoundIndexDefinition(
                        new Document("userId", 1).append("operation", 1).append("keyHash", 1))
                .named("uq_vault_reassignment_user_operation_key")
                .unique());
        indexOps.ensureIndex(new Index().on("expiresAt", Sort.Direction.ASC)
                .named("idx_vault_reassignment_expires_at").expire(Duration.ZERO));
    }
}
