package com.fintrack.vault.service;

import com.fintrack.common.exception.ConflictException;
import com.fintrack.common.exception.ResourceNotFoundException;
import com.fintrack.transaction.domain.Transaction;
import com.fintrack.transaction.repository.TransactionRepository;
import com.fintrack.vault.domain.VaultDocument;
import com.fintrack.vault.domain.VaultDocumentStatus;
import com.fintrack.vault.domain.VaultDocumentType;
import com.fintrack.vault.repository.VaultDocumentRepository;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;

/**
 * Document-level claim/release/finalize mechanics for {@code vault.reassign}, split out of
 * {@link VaultReassignmentService} so that orchestration and document mutation are separate
 * units. Stateless static methods, not a Spring bean — {@link VaultReassignmentServiceTest}
 * uses {@code @InjectMocks} with a fixed mock set matching the service's exact constructor,
 * so a new bean dependency there would come up {@code null} at runtime; passing collaborators
 * in as parameters avoids that.
 */
final class VaultDocumentClaimFinalizer {

    private VaultDocumentClaimFinalizer() {
    }

    static VaultDocument findOwned(VaultDocumentRepository vaultDocumentRepository, Long userId, String documentId) {
        return vaultDocumentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> ResourceNotFoundException.of("VaultDocument", documentId));
    }

    static void ensureDifferentAccount(VaultDocument document, Long targetAccountId) {
        if (targetAccountId.equals(document.getAccountId())) {
            throw new ConflictException("Vault document is already assigned to this account");
        }
    }

    static VaultDocument claim(MongoTemplate mongoTemplate, VaultDocument document, String operationId) {
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

    static void clearClaim(MongoTemplate mongoTemplate, Long userId, String documentId, String operationId) {
        Query query = Query.query(Criteria.where("_id").is(documentId)
                .and("userId").is(userId)
                .and("reassignmentOperationId").is(operationId));
        mongoTemplate.updateFirst(query, new Update()
                .set("reassignmentOperationId", null)
                .set("reassignmentStartedAt", null), VaultDocument.class);
    }

    static VaultDocument finalizeDocument(VaultDocumentRepository vaultDocumentRepository,
                                          TransactionRepository transactionRepository,
                                          Long userId, VaultDocument document, String operationId, Long targetAccountId) {
        VaultDocument current = findOwned(vaultDocumentRepository, userId, document.getId());
        if (!operationId.equals(current.getReassignmentOperationId())) {
            throw new ConflictException("Vault reassignment claim was lost");
        }
        if (current.getTransactionId() != null
                && manualLinkMustDetach(transactionRepository, userId, current, targetAccountId)) {
            current.setTransactionId(null);
        }
        if (current.getType() == VaultDocumentType.STATEMENT) {
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

    static boolean manualLinkMustDetach(TransactionRepository transactionRepository, Long userId,
                                        VaultDocument document, Long targetAccountId) {
        if (document.getTransactionId() == null) {
            return false;
        }
        return transactionRepository.findByIdAndUserId(document.getTransactionId(), userId)
                .map(Transaction::getAccount)
                .map(account -> !targetAccountId.equals(account.getId()))
                .orElse(true);
    }
}
