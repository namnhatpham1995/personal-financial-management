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
import com.fintrack.vault.domain.VaultOperation;
import com.fintrack.vault.domain.VaultOperationState;
import com.fintrack.vault.repository.VaultDocumentRepository;
import com.fintrack.vault.repository.VaultOperationRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class VaultReassignmentService {

    private static final String OPERATION_NAME = "vault.reassign";
    private static final Duration RESULT_RETENTION = Duration.ofDays(7);

    private static final String DOCUMENT_ID = "documentId";
    private static final String SOURCE_ACCOUNT_ID = "sourceAccountId";
    private static final String TARGET_ACCOUNT_ID = "targetAccountId";
    private static final String REMOVED_TRANSACTION_IDS = "removedTransactionIds";
    private static final String REMOVED_TRANSACTION_COUNT = "removedTransactionCount";
    private static final String MANUAL_LINK_DETACHED = "manualLinkDetached";
    private static final String FAILURE_REASON = "failureReason";

    private final VaultDocumentRepository vaultDocumentRepository;
    private final VaultOperationRepository operationRepository;
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
                Map.of(DOCUMENT_ID, documentId, TARGET_ACCOUNT_ID, request.targetAccountId()));
        VaultOperation operation = claimOperation(userId, documentId,
                request.targetAccountId(), keyHash, requestHash);

        if (operation.getState() == VaultOperationState.COMPLETED) {
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
        Map<String, Object> payload = payload(operation);
        payload.put(SOURCE_ACCOUNT_ID, claimed.getAccountId());
        payload.put(REMOVED_TRANSACTION_IDS, new ArrayList<>(importedIds));
        payload.put(REMOVED_TRANSACTION_COUNT, importedIds.size());
        payload.put(MANUAL_LINK_DETACHED, manualLinkMustDetach(userId, claimed, target.getId()));
        operationRepository.save(operation);

        try {
            VaultReassignmentCleanupService.CleanupResult cleanup = cleanupService.clean(
                    userId, documentId, claimed.getAccountId(), importedIds,
                    "Vault document reassigned to another account");
            payload.put(REMOVED_TRANSACTION_COUNT, cleanup.removedTransactions());
            operationRepository.save(operation);
            VaultDocument finalized = finalizeDocument(userId, claimed, operation.getId(), target.getId());
            operation.setState(VaultOperationState.COMPLETED);
            operation.setCompletedAt(Instant.now());
            operationRepository.save(operation);
            auditReplaySignal.setOperationReference(operation.getId());
            metrics.reassignmentCompleted();
            return new VaultReassignmentResponse(
                    vaultService.getById(userId, finalized.getId()),
                    integerValue(payload.get(REMOVED_TRANSACTION_COUNT)),
                    Boolean.TRUE.equals(payload.get(MANUAL_LINK_DETACHED)), false);
        } catch (RuntimeException e) {
            metrics.reassignmentCleanupFailed();
            failOperation(operation, e.getMessage());
            clearClaim(userId, documentId, operation.getId());
            throw e;
        }
    }

    /** Resumes a stale operation after a process interruption; called by the bounded scheduler. */
    public void recoverStale(VaultOperation operation) {
        if (operation.getState() != VaultOperationState.PROCESSING) {
            return;
        }
        String documentId = requiredString(operation, DOCUMENT_ID);
        Long targetAccountId = requiredLong(operation, TARGET_ACCOUNT_ID);
        Long sourceAccountId = nullableLong(payload(operation).get(SOURCE_ACCOUNT_ID));
        VaultDocument document;
        try {
            document = findOwned(operation.getUserId(), documentId);
            if (targetAccountId.equals(document.getAccountId())
                    && document.getReassignmentOperationId() == null) {
                operation.setState(VaultOperationState.COMPLETED);
                operation.setCompletedAt(Instant.now());
                operationRepository.save(operation);
                metrics.reassignmentRecovered();
                return;
            }
            Account target = accountService.findOwned(operation.getUserId(), targetAccountId);
            if (document.getStatus() == VaultDocumentStatus.CONFIRMING) {
                failOperation(operation, "Statement confirmation is still processing");
                clearClaim(operation.getUserId(), documentId, operation.getId());
                return;
            }
            VaultDocument claimed = document.getReassignmentOperationId() == null
                    ? claimDocument(document, operation.getId()) : document;
            if (claimed == null) {
                return;
            }
            List<Long> storedIds = transactionIds(payload(operation).get(REMOVED_TRANSACTION_IDS));
            Set<Long> ids = storedIds.isEmpty()
                    ? importOriginService.collectTransactionIds(operation.getUserId(), claimed)
                    : Set.copyOf(storedIds);
            VaultReassignmentCleanupService.CleanupResult cleanup = cleanupService.clean(
                    operation.getUserId(), documentId, sourceAccountId, ids,
                    "Vault document reassigned to another account");
            payload(operation).put(REMOVED_TRANSACTION_COUNT, cleanup.removedTransactions());
            finalizeDocument(operation.getUserId(), claimed, operation.getId(), target.getId());
            operation.setState(VaultOperationState.COMPLETED);
            operation.setCompletedAt(Instant.now());
            operationRepository.save(operation);
            metrics.reassignmentRecovered();
        } catch (RuntimeException e) {
            failOperation(operation, e.getMessage());
            clearClaim(operation.getUserId(), documentId, operation.getId());
        }
    }

    private VaultReassignmentResponse replay(VaultOperation operation, Long userId) {
        Map<String, Object> payload = payload(operation);
        return new VaultReassignmentResponse(
                vaultService.getById(userId, requiredString(operation, DOCUMENT_ID)),
                integerValue(payload.get(REMOVED_TRANSACTION_COUNT)),
                Boolean.TRUE.equals(payload.get(MANUAL_LINK_DETACHED)), true);
    }

    private VaultOperation claimOperation(Long userId, String documentId, Long targetAccountId,
                                          String keyHash, String requestHash) {
        Instant now = Instant.now();
        VaultOperation candidate = VaultOperation.builder()
                .userId(userId)
                .operation(OPERATION_NAME)
                .keyHash(keyHash)
                .requestHash(requestHash)
                .payload(initialPayload(documentId, targetAccountId))
                .state(VaultOperationState.PROCESSING)
                .createdAt(now)
                .expiresAt(now.plus(RESULT_RETENTION))
                .build();
        try {
            return operationRepository.insert(candidate);
        } catch (DuplicateKeyException e) {
            VaultOperation existing = operationRepository
                    .findByUserIdAndOperationAndKeyHash(userId, OPERATION_NAME, keyHash)
                    .orElseThrow(() -> new IllegalStateException("Reassignment operation disappeared after claim conflict"));
            if (!requestHash.equals(existing.getRequestHash())) {
                throw new IdempotencyConflictException(
                        "Idempotency-Key was already used for a different Vault reassignment");
            }
            if (existing.getState() == VaultOperationState.COMPLETED) {
                return existing;
            }
            if (existing.getState() == VaultOperationState.FAILED) {
                existing.setState(VaultOperationState.PROCESSING);
                existing.setCreatedAt(now);
                existing.setCompletedAt(null);
                existing.setPayload(initialPayload(documentId, targetAccountId));
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

    private void failOperation(VaultOperation operation, String reason) {
        operation.setState(VaultOperationState.FAILED);
        payload(operation).put(FAILURE_REASON, reason);
        operationRepository.save(operation);
    }

    private Map<String, Object> initialPayload(String documentId, Long targetAccountId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(DOCUMENT_ID, documentId);
        payload.put(TARGET_ACCOUNT_ID, targetAccountId);
        return payload;
    }

    private Map<String, Object> payload(VaultOperation operation) {
        if (operation.getPayload() == null) {
            operation.setPayload(new LinkedHashMap<>());
        }
        return operation.getPayload();
    }

    private String requiredString(VaultOperation operation, String key) {
        Object value = payload(operation).get(key);
        if (value == null) {
            throw new IllegalStateException("Reassignment operation is missing payload field " + key);
        }
        return String.valueOf(value);
    }

    private Long requiredLong(VaultOperation operation, String key) {
        Long value = nullableLong(payload(operation).get(key));
        if (value == null) {
            throw new IllegalStateException("Reassignment operation is missing payload field " + key);
        }
        return value;
    }

    private static Long nullableLong(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(value));
    }

    private static int integerValue(Object value) {
        if (value == null) {
            return 0;
        }
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private static List<Long> transactionIds(Object raw) {
        if (!(raw instanceof Iterable<?> values)) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (Object value : values) {
            ids.add(nullableLong(value));
        }
        return ids;
    }

    private void ensureIndexesOnce() {
        if (!indexesEnsured.compareAndSet(false, true)) {
            return;
        }
        var indexOps = mongoTemplate.indexOps(VaultOperation.class);
        indexOps.ensureIndex(new CompoundIndexDefinition(
                        new Document("userId", 1).append("operation", 1).append("keyHash", 1))
                .named("uq_vault_operations_user_operation_key")
                .unique());
        indexOps.ensureIndex(new Index().on("expiresAt", Sort.Direction.ASC)
                .named("idx_vault_operations_expires_at").expire(Duration.ZERO));
    }
}
