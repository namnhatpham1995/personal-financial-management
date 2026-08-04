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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
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
