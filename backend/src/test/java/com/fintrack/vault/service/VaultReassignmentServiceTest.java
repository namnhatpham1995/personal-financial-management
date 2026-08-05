package com.fintrack.vault.service;

import com.fintrack.account.domain.Account;
import com.fintrack.account.domain.AccountType;
import com.fintrack.account.service.AccountService;
import com.fintrack.audit.support.AuditReplaySignal;
import com.fintrack.idempotency.service.IdempotencyHasher;
import com.fintrack.idempotency.service.IdempotencyKeyValidator;
import com.fintrack.transaction.repository.TransactionRepository;
import com.fintrack.transaction.domain.Transaction;
import com.fintrack.vault.domain.VaultDocument;
import com.fintrack.vault.domain.VaultDocumentStatus;
import com.fintrack.vault.domain.VaultDocumentType;
import com.fintrack.vault.domain.VaultOperation;
import com.fintrack.vault.domain.VaultOperationState;
import com.fintrack.vault.repository.VaultDocumentRepository;
import com.fintrack.vault.repository.VaultOperationRepository;
import com.fintrack.vault.web.dto.VaultDocumentResponse;
import com.fintrack.vault.web.dto.VaultReassignmentRequest;
import com.fintrack.idempotency.exception.IdempotencyConflictException;
import com.fintrack.idempotency.exception.IdempotencyOperationInProgressException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fintrack.common.exception.ConflictException;

@ExtendWith(MockitoExtension.class)
class VaultReassignmentServiceTest {

    @Mock VaultDocumentRepository vaultDocumentRepository;
    @Mock VaultOperationRepository operationRepository;
    @Mock VaultImportOriginService importOriginService;
    @Mock VaultReassignmentCleanupService cleanupService;
    @Mock VaultService vaultService;
    @Mock AccountService accountService;
    @Mock TransactionRepository transactionRepository;
    @Mock IdempotencyKeyValidator keyValidator;
    @Mock IdempotencyHasher hasher;
    @Mock MongoTemplate mongoTemplate;
    @Mock IndexOperations indexOperations;
    @Mock AuditReplaySignal auditReplaySignal;
    @Mock VaultOperationMetrics metrics;
    @InjectMocks VaultReassignmentService reassignmentService;

    @Test
    void preview_countsImportedTransactionsAndWarnsOnCurrencyChange() {
        VaultDocument document = document(VaultDocumentType.STATEMENT, 10L);
        Account source = account(10L, "Checking", "USD");
        Account target = account(20L, "Savings", "EUR");
        when(vaultDocumentRepository.findByIdAndUserId("doc-1", 1L)).thenReturn(Optional.of(document));
        when(accountService.findOwned(1L, 10L)).thenReturn(source);
        when(accountService.findOwned(1L, 20L)).thenReturn(target);
        when(importOriginService.collectTransactionIds(1L, document)).thenReturn(Set.of(11L, 12L));

        var preview = reassignmentService.preview(1L, "doc-1", 20L);

        assertThat(preview.importedTransactionCount()).isEqualTo(2);
        assertThat(preview.currencyChanged()).isTrue();
        assertThat(preview.sourceAccountName()).isEqualTo("Checking");
        assertThat(preview.targetAccountName()).isEqualTo("Savings");
    }

    @Test
    void preview_reportsManualLinkDetachmentAndRejectsSameAccount() {
        VaultDocument document = document(VaultDocumentType.RECEIPT, 10L);
        document.setTransactionId(99L);
        Account source = account(10L, "Checking", "USD");
        Account target = account(20L, "Savings", "USD");
        Transaction linked = Transaction.builder().id(99L).account(source).build();
        when(vaultDocumentRepository.findByIdAndUserId("doc-1", 1L)).thenReturn(Optional.of(document));
        when(accountService.findOwned(1L, 10L)).thenReturn(source);
        when(accountService.findOwned(1L, 20L)).thenReturn(target);
        when(importOriginService.collectTransactionIds(1L, document)).thenReturn(Set.of());
        when(transactionRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.of(linked));

        assertThat(reassignmentService.preview(1L, "doc-1", 20L).detachManualLink()).isTrue();
        when(accountService.findOwned(1L, 10L)).thenReturn(source);
        assertThatThrownBy(() -> reassignmentService.preview(1L, "doc-1", 10L))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void reassign_removesImportsBeforeResettingStatementAndMovingAccount() {
        VaultDocument document = document(VaultDocumentType.STATEMENT, 10L);
        document.setStatus(VaultDocumentStatus.STAGED);
        document.setPayload(Map.of("rows", "parsed"));
        Account target = account(20L, "Savings", "USD");
        when(hasher.hashKey("key-123456789012")).thenReturn("key-hash");
        when(hasher.hashJsonRequest(eq("vault.reassign"), any())).thenReturn("request-hash");
        when(mongoTemplate.indexOps(VaultOperation.class)).thenReturn(indexOperations);
        when(operationRepository.insert(any(VaultOperation.class))).thenAnswer(invocation -> {
            VaultOperation operation = invocation.getArgument(0);
            operation.setId("operation-1");
            return operation;
        });
        when(vaultDocumentRepository.findByIdAndUserId("doc-1", 1L))
                .thenReturn(Optional.of(document), Optional.of(document));
        when(accountService.findOwned(1L, 20L)).thenReturn(target);
        when(importOriginService.collectTransactionIds(1L, document)).thenReturn(Set.of(11L, 12L));
        VaultDocument claimed = document;
        claimed.setReassignmentOperationId("operation-1");
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(VaultDocument.class)))
                .thenReturn(claimed);
        when(cleanupService.clean(eq(1L), eq("doc-1"), eq(10L), eq(Set.of(11L, 12L)), any()))
                .thenReturn(new VaultReassignmentCleanupService.CleanupResult(2, 1));
        when(vaultDocumentRepository.save(any(VaultDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(vaultService.getById(1L, "doc-1")).thenReturn(new VaultDocumentResponse(
                "doc-1", 20L, VaultDocumentType.STATEMENT, VaultDocumentStatus.UPLOADED,
                "csv", Instant.now(), null, true, "statement.csv", null, null));

        var result = reassignmentService.reassign(1L, "doc-1",
                new VaultReassignmentRequest(20L), "key-123456789012");

        assertThat(result.removedImportedTransactions()).isEqualTo(2);
        assertThat(result.document().accountId()).isEqualTo(20L);
        assertThat(result.replayed()).isFalse();
        assertThat(document.getStatus()).isEqualTo(VaultDocumentStatus.UPLOADED);
        assertThat(document.getPayload()).isNull();
        assertThat(document.getReassignmentOperationId()).isNull();
        verify(cleanupService).clean(eq(1L), eq("doc-1"), eq(10L), eq(Set.of(11L, 12L)), any());
        ArgumentCaptor<VaultOperation> saved = ArgumentCaptor.forClass(VaultOperation.class);
        verify(operationRepository, atLeastOnce()).save(saved.capture());
        VaultOperation completed = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(completed.getState()).isEqualTo(VaultOperationState.COMPLETED);
        assertThat(completed.getPayload())
                .containsEntry("documentId", "doc-1")
                .containsEntry("sourceAccountId", 10L)
                .containsEntry("targetAccountId", 20L)
                .containsEntry("removedTransactionCount", 2)
                .containsEntry("manualLinkDetached", false);
    }

    @Test
    void reclaimRace_winnerReclaimsFailedOperationAtomically_completesReassignment() {
        VaultDocument document = document(VaultDocumentType.STATEMENT, 10L);
        document.setStatus(VaultDocumentStatus.STAGED);
        document.setPayload(Map.of("rows", "parsed"));
        Account target = account(20L, "Savings", "USD");
        VaultOperation existingFailed = VaultOperation.builder()
                .id("operation-1").userId(1L).operation("vault.reassign")
                .keyHash("key-hash").requestHash("request-hash")
                .state(VaultOperationState.FAILED)
                .build();
        VaultOperation reclaimed = VaultOperation.builder()
                .id("operation-1").userId(1L).operation("vault.reassign")
                .keyHash("key-hash").requestHash("request-hash")
                .state(VaultOperationState.PROCESSING)
                .payload(new LinkedHashMap<>(Map.of("documentId", "doc-1", "targetAccountId", 20L)))
                .build();

        when(hasher.hashKey("key-123456789012")).thenReturn("key-hash");
        when(hasher.hashJsonRequest(eq("vault.reassign"), any())).thenReturn("request-hash");
        when(mongoTemplate.indexOps(VaultOperation.class)).thenReturn(indexOperations);
        when(operationRepository.insert(any(VaultOperation.class))).thenThrow(new DuplicateKeyException("dup"));
        when(operationRepository.findByUserIdAndOperationAndKeyHash(1L, "vault.reassign", "key-hash"))
                .thenReturn(Optional.of(existingFailed));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(VaultOperation.class)))
                .thenReturn(reclaimed);
        when(vaultDocumentRepository.findByIdAndUserId("doc-1", 1L))
                .thenReturn(Optional.of(document), Optional.of(document));
        when(accountService.findOwned(1L, 20L)).thenReturn(target);
        when(importOriginService.collectTransactionIds(1L, document)).thenReturn(Set.of(11L, 12L));
        VaultDocument claimed = document;
        claimed.setReassignmentOperationId("operation-1");
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(VaultDocument.class)))
                .thenReturn(claimed);
        when(cleanupService.clean(eq(1L), eq("doc-1"), eq(10L), eq(Set.of(11L, 12L)), any()))
                .thenReturn(new VaultReassignmentCleanupService.CleanupResult(2, 1));
        when(vaultDocumentRepository.save(any(VaultDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(vaultService.getById(1L, "doc-1")).thenReturn(new VaultDocumentResponse(
                "doc-1", 20L, VaultDocumentType.STATEMENT, VaultDocumentStatus.UPLOADED,
                "csv", Instant.now(), null, true, "statement.csv", null, null));

        var result = reassignmentService.reassign(1L, "doc-1",
                new VaultReassignmentRequest(20L), "key-123456789012");

        assertThat(result.removedImportedTransactions()).isEqualTo(2);
        assertThat(result.replayed()).isFalse();
        assertThat(reclaimed.getState()).isEqualTo(VaultOperationState.COMPLETED);
        // The reclaim itself was never a plain save() on the FAILED row — only the atomic CAS.
        verify(operationRepository, never()).save(existingFailed);
    }

    @Test
    void reclaimRace_loserGetsInProgressException_doesNotMutateOperation() {
        VaultOperation existingFailed = VaultOperation.builder()
                .id("operation-1").userId(1L).operation("vault.reassign")
                .keyHash("key-hash").requestHash("request-hash")
                .state(VaultOperationState.FAILED)
                .build();
        VaultOperation nowProcessing = VaultOperation.builder()
                .id("operation-1").userId(1L).operation("vault.reassign")
                .keyHash("key-hash").requestHash("request-hash")
                .state(VaultOperationState.PROCESSING)
                .build();

        when(hasher.hashKey("key-123456789012")).thenReturn("key-hash");
        when(hasher.hashJsonRequest(eq("vault.reassign"), any())).thenReturn("request-hash");
        when(mongoTemplate.indexOps(VaultOperation.class)).thenReturn(indexOperations);
        when(operationRepository.insert(any(VaultOperation.class))).thenThrow(new DuplicateKeyException("dup"));
        when(operationRepository.findByUserIdAndOperationAndKeyHash(1L, "vault.reassign", "key-hash"))
                .thenReturn(Optional.of(existingFailed), Optional.of(nowProcessing));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(VaultOperation.class)))
                .thenReturn(null);

        // Never a spurious 409: the winner already reclaimed under this key, so the loser must
        // see PROCESSING (in-progress), not be treated as a payload conflict.
        assertThatThrownBy(() -> reassignmentService.reassign(1L, "doc-1",
                new VaultReassignmentRequest(20L), "key-123456789012"))
                .isInstanceOf(IdempotencyOperationInProgressException.class);

        // The loser never touches the operation row: it does not mark it FAILED and does not
        // erase the winner's recorded source account, removed-transaction ids, or count.
        verify(operationRepository, never()).save(any(VaultOperation.class));
    }

    @Test
    void failedOperation_retriedWithDifferentTarget_startsFreshAttemptInsteadOf409() {
        VaultDocument document = document(VaultDocumentType.RECEIPT, 10L);
        Account target = account(30L, "Vacation", "USD");
        VaultOperation existingFailed = VaultOperation.builder()
                .id("operation-1").userId(1L).operation("vault.reassign")
                .keyHash("key-hash").requestHash("old-request-hash")
                .state(VaultOperationState.FAILED)
                .build();
        VaultOperation reclaimed = VaultOperation.builder()
                .id("operation-1").userId(1L).operation("vault.reassign")
                .keyHash("key-hash").requestHash("new-request-hash")
                .state(VaultOperationState.PROCESSING)
                .payload(new LinkedHashMap<>(Map.of("documentId", "doc-1", "targetAccountId", 30L)))
                .build();

        when(hasher.hashKey("key-123456789012")).thenReturn("key-hash");
        when(hasher.hashJsonRequest(eq("vault.reassign"), any())).thenReturn("new-request-hash");
        when(mongoTemplate.indexOps(VaultOperation.class)).thenReturn(indexOperations);
        when(operationRepository.insert(any(VaultOperation.class))).thenThrow(new DuplicateKeyException("dup"));
        when(operationRepository.findByUserIdAndOperationAndKeyHash(1L, "vault.reassign", "key-hash"))
                .thenReturn(Optional.of(existingFailed));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(VaultOperation.class)))
                .thenReturn(reclaimed);
        when(vaultDocumentRepository.findByIdAndUserId("doc-1", 1L))
                .thenReturn(Optional.of(document), Optional.of(document));
        when(accountService.findOwned(1L, 30L)).thenReturn(target);
        when(importOriginService.collectTransactionIds(1L, document)).thenReturn(Set.of());
        VaultDocument claimed = document;
        claimed.setReassignmentOperationId("operation-1");
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(VaultDocument.class)))
                .thenReturn(claimed);
        when(cleanupService.clean(eq(1L), eq("doc-1"), eq(10L), eq(Set.of()), any()))
                .thenReturn(new VaultReassignmentCleanupService.CleanupResult(0, 0));
        when(vaultDocumentRepository.save(any(VaultDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(vaultService.getById(1L, "doc-1")).thenReturn(new VaultDocumentResponse(
                "doc-1", 30L, VaultDocumentType.RECEIPT, VaultDocumentStatus.ACTIVE,
                "receipt", Instant.now(), null, true, "receipt.png", null, null));

        // Old failed attempt targeted a different account (different requestHash); a key bound
        // to a FAILED attempt is not yet bound to that attempt's payload, so this must succeed
        // as a fresh attempt rather than 409 on the payload mismatch.
        var result = reassignmentService.reassign(1L, "doc-1",
                new VaultReassignmentRequest(30L), "key-123456789012");

        assertThat(result.replayed()).isFalse();
        assertThat(result.document().accountId()).isEqualTo(30L);
    }

    @Test
    void completedOperation_retriedWithDifferentPayload_returns409() {
        VaultOperation existingCompleted = VaultOperation.builder()
                .id("operation-1").userId(1L).operation("vault.reassign")
                .keyHash("key-hash").requestHash("old-request-hash")
                .state(VaultOperationState.COMPLETED)
                .build();

        when(hasher.hashKey("key-123456789012")).thenReturn("key-hash");
        when(hasher.hashJsonRequest(eq("vault.reassign"), any())).thenReturn("new-request-hash");
        when(mongoTemplate.indexOps(VaultOperation.class)).thenReturn(indexOperations);
        when(operationRepository.insert(any(VaultOperation.class))).thenThrow(new DuplicateKeyException("dup"));
        when(operationRepository.findByUserIdAndOperationAndKeyHash(1L, "vault.reassign", "key-hash"))
                .thenReturn(Optional.of(existingCompleted));

        // A key already bound to a COMPLETED reassignment IS bound to that attempt's payload —
        // unlike the FAILED case, a different target account must still 409.
        assertThatThrownBy(() -> reassignmentService.reassign(1L, "doc-1",
                new VaultReassignmentRequest(30L), "key-123456789012"))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void indexCreationFails_retriedOnNextCall_ratherThanDisablingProtectionForever() {
        when(mongoTemplate.indexOps(VaultOperation.class)).thenReturn(indexOperations);
        when(indexOperations.ensureIndex(any()))
                .thenThrow(new RuntimeException("mongo temporarily unavailable"))
                .thenReturn("idx");

        // First call: index creation fails, so the whole operation fails without claiming.
        assertThatThrownBy(() -> reassignmentService.reassign(1L, "doc-1",
                new VaultReassignmentRequest(20L), "key-123456789012"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("mongo temporarily unavailable");
        verify(operationRepository, never()).insert(any(VaultOperation.class));

        // Second call: the guard flag was released on failure, so index creation is retried.
        // Reaching the key-hash lookup (the next real step of reassign()) proves ensureIndexesOnce
        // did not throw again and did not silently skip creation.
        when(hasher.hashKey(any())).thenThrow(new IllegalStateException("reached claim path"));
        assertThatThrownBy(() -> reassignmentService.reassign(1L, "doc-1",
                new VaultReassignmentRequest(20L), "key-123456789012"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("reached claim path");

        // Unique index + TTL index, attempted once on the failed call and twice on the retry.
        verify(indexOperations, times(3)).ensureIndex(any());
    }

    @Test
    void recoverStale_readsPayloadWrittenBeforeTypedPayloadRefactor_resumesUsingStoredFields() {
        // Simulates a PROCESSING row a pre-refactor deployment wrote: a plain, untyped payload map
        // with the claim details already recorded but the cleanup count not yet written (crashed
        // between claim and cleanup completion). VaultReassignmentPayload.fromMap must parse this
        // exact shape identically to what the old Map<String, Object> access did.
        Map<String, Object> legacyPayload = new LinkedHashMap<>();
        legacyPayload.put("documentId", "doc-1");
        legacyPayload.put("targetAccountId", 20L);
        legacyPayload.put("sourceAccountId", 10L);
        legacyPayload.put("removedTransactionIds", List.of(11L, 12L));
        legacyPayload.put("manualLinkDetached", false);
        VaultOperation stale = VaultOperation.builder()
                .id("operation-1").userId(1L).operation("vault.reassign")
                .keyHash("key-hash").requestHash("request-hash")
                .state(VaultOperationState.PROCESSING)
                .payload(legacyPayload)
                .build();

        VaultDocument document = document(VaultDocumentType.STATEMENT, 10L);
        document.setStatus(VaultDocumentStatus.STAGED);
        document.setReassignmentOperationId("operation-1");
        Account target = account(20L, "Savings", "USD");

        when(vaultDocumentRepository.findByIdAndUserId("doc-1", 1L))
                .thenReturn(Optional.of(document), Optional.of(document));
        when(accountService.findOwned(1L, 20L)).thenReturn(target);
        when(cleanupService.clean(eq(1L), eq("doc-1"), eq(10L), eq(Set.of(11L, 12L)), any()))
                .thenReturn(new VaultReassignmentCleanupService.CleanupResult(2, 1));
        when(vaultDocumentRepository.save(any(VaultDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        reassignmentService.recoverStale(stale);

        assertThat(stale.getState()).isEqualTo(VaultOperationState.COMPLETED);
        // The stored removedTransactionIds is reused rather than re-collected from scratch.
        verifyNoInteractions(importOriginService);
        verify(cleanupService).clean(eq(1L), eq("doc-1"), eq(10L), eq(Set.of(11L, 12L)), any());
        assertThat(stale.getPayload()).containsEntry("removedTransactionCount", 2);
    }

    private static VaultDocument document(VaultDocumentType type, Long accountId) {
        return VaultDocument.builder()
                .id("doc-1")
                .userId(1L)
                .accountId(accountId)
                .type(type)
                .status(VaultDocumentStatus.ACTIVE)
                .source("manual")
                .capturedAt(Instant.now())
                .originalFilename("statement.csv")
                .build();
    }

    private static Account account(Long id, String name, String currency) {
        return Account.builder().id(id).name(name).accountType(AccountType.BANK).currency(currency).build();
    }
}
