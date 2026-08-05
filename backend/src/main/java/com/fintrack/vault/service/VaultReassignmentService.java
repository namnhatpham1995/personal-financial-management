package com.fintrack.vault.service;

import com.fintrack.account.domain.Account;
import com.fintrack.account.service.AccountService;
import com.fintrack.audit.support.AuditReplaySignal;
import com.fintrack.common.exception.ConflictException;
import com.fintrack.common.exception.ResourceNotFoundException;
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
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class VaultReassignmentService {

    private static final String OPERATION_NAME = "vault.reassign";
    private static final Duration RESULT_RETENTION = Duration.ofDays(7);

    /** Mirrors upload's bounded poll: ~150ms steps, 3s total, adopted in place of an immediate
     * in-progress throw so a request that arrives just after another one started no longer needs
     * a client-side retry to observe the same result. */
    private static final Duration POLL_BOUND = Duration.ofSeconds(3);

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

    /**
     * Guards one-time, on-first-use index creation (see {@link #ensureIndexesOnce()}). Set to
     * {@code true} only once index creation has actually succeeded — never before the attempt, so
     * a transient Mongo failure leaves this {@code false} and the next call retries, instead of
     * silently disabling the unique-claim index (and same-key protection with it) for the
     * remaining life of the process.
     */
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
                Map.of(VaultReassignmentPayload.DOCUMENT_ID, documentId,
                        VaultReassignmentPayload.TARGET_ACCOUNT_ID, request.targetAccountId()));
        VaultOperationClaimCoordinator coordinator = new VaultOperationClaimCoordinator(operationRepository, mongoTemplate);
        VaultOperationClaimCoordinator.ClaimOutcome outcome = coordinator.claim(userId, OPERATION_NAME, keyHash, requestHash,
                POLL_BOUND,
                () -> initialCandidate(userId, documentId, request.targetAccountId(), keyHash, requestHash),
                (existing, now) -> reclaimUpdate(documentId, request.targetAccountId(), requestHash, now),
                "Idempotency-Key was already used for a different Vault reassignment",
                "Another request with this Idempotency-Key is still reassigning the document", 1);

        if (!outcome.owned()) {
            metrics.replayed(OPERATION_NAME);
            auditReplaySignal.markReplayed();
            return replay(outcome.operation(), userId);
        }
        VaultOperation operation = outcome.operation();

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
        VaultReassignmentPayload payload = VaultReassignmentPayload.fromMap(operation.getPayload())
                .withClaimDetails(claimed.getAccountId(), List.copyOf(importedIds), importedIds.size(),
                        manualLinkMustDetach(userId, claimed, target.getId()));
        operation.setPayload(payload.toMap());
        operationRepository.save(operation);

        try {
            VaultReassignmentCleanupService.CleanupResult cleanup = cleanupService.clean(
                    userId, documentId, claimed.getAccountId(), importedIds,
                    "Vault document reassigned to another account");
            payload = payload.withRemovedTransactionCount(cleanup.removedTransactions());
            operation.setPayload(payload.toMap());
            operationRepository.save(operation);
            VaultDocument finalized = finalizeDocument(userId, claimed, operation.getId(), target.getId());
            operation.setState(VaultOperationState.COMPLETED);
            operation.setCompletedAt(Instant.now());
            operationRepository.save(operation);
            auditReplaySignal.setOperationReference(operation.getId());
            metrics.reassignmentCompleted();
            return new VaultReassignmentResponse(
                    vaultService.getById(userId, finalized.getId()),
                    payload.removedTransactionCountOrZero(),
                    payload.manualLinkDetachedOrFalse(), false);
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
        VaultReassignmentPayload payload = VaultReassignmentPayload.fromMap(operation.getPayload());
        String documentId = requireField(payload.documentId(), VaultReassignmentPayload.DOCUMENT_ID);
        Long targetAccountId = requireField(payload.targetAccountId(), VaultReassignmentPayload.TARGET_ACCOUNT_ID);
        Long sourceAccountId = payload.sourceAccountId();
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
            List<Long> storedIds = payload.removedTransactionIds();
            Set<Long> ids = storedIds == null || storedIds.isEmpty()
                    ? importOriginService.collectTransactionIds(operation.getUserId(), claimed)
                    : Set.copyOf(storedIds);
            VaultReassignmentCleanupService.CleanupResult cleanup = cleanupService.clean(
                    operation.getUserId(), documentId, sourceAccountId, ids,
                    "Vault document reassigned to another account");
            operation.setPayload(payload.withRemovedTransactionCount(cleanup.removedTransactions()).toMap());
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
        VaultReassignmentPayload payload = VaultReassignmentPayload.fromMap(operation.getPayload());
        return new VaultReassignmentResponse(
                vaultService.getById(userId, requireField(payload.documentId(), VaultReassignmentPayload.DOCUMENT_ID)),
                payload.removedTransactionCountOrZero(),
                payload.manualLinkDetachedOrFalse(), true);
    }

    private VaultOperation initialCandidate(Long userId, String documentId, Long targetAccountId,
                                            String keyHash, String requestHash) {
        Instant now = Instant.now();
        return VaultOperation.builder()
                .userId(userId)
                .operation(OPERATION_NAME)
                .keyHash(keyHash)
                .requestHash(requestHash)
                .payload(VaultReassignmentPayload.initial(documentId, targetAccountId).toMap())
                .state(VaultOperationState.PROCESSING)
                .createdAt(now)
                .expiresAt(now.plus(RESULT_RETENTION))
                .build();
    }

    /**
     * Builds the reclaim update for a FAILED operation. A key bound to a FAILED attempt is not
     * yet bound to that attempt's payload — retrying with a different target account starts a
     * fresh attempt rather than 409ing, matching the upload and Postgres mutation paths.
     */
    private Update reclaimUpdate(String documentId, Long targetAccountId, String requestHash, Instant now) {
        return new Update()
                .set("state", VaultOperationState.PROCESSING)
                .set("requestHash", requestHash)
                .set("payload", VaultReassignmentPayload.initial(documentId, targetAccountId).toMap())
                .set("createdAt", now)
                .set("expiresAt", now.plus(RESULT_RETENTION))
                .set("completedAt", null);
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
        operation.setPayload(VaultReassignmentPayload.fromMap(operation.getPayload())
                .withFailureReason(reason)
                .toMap());
        operationRepository.save(operation);
    }

    private static <T> T requireField(T value, String fieldName) {
        if (value == null) {
            throw new IllegalStateException("Reassignment operation is missing payload field " + fieldName);
        }
        return value;
    }

    /** Delegates to {@link VaultOperationClaimCoordinator#ensureIndexesOnce} — see that method's
     * javadoc for why this class keeps the guard flag itself. */
    private void ensureIndexesOnce() {
        VaultOperationClaimCoordinator.ensureIndexesOnce(indexesEnsured, mongoTemplate);
    }
}
