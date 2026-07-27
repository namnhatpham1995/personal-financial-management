package com.fintrack.vault.service;

import com.fintrack.agent.domain.AgentRun;
import com.fintrack.agent.domain.AgentRunStatus;
import com.fintrack.agent.repository.AgentRunRepository;
import com.fintrack.account.service.AccountService;
import com.fintrack.common.exception.ResourceNotFoundException;
import com.fintrack.vault.domain.VaultDocument;
import com.fintrack.vault.domain.VaultDocumentStatus;
import com.fintrack.vault.domain.VaultDocumentType;
import com.fintrack.vault.repository.VaultDocumentRepository;
import com.fintrack.vault.web.dto.VaultDocumentResponse;
import com.fintrack.vault.web.dto.VaultSearchRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VaultServiceTest {

    @Mock VaultDocumentRepository vaultDocumentRepository;
    @Mock GridFsFileStore gridFsFileStore;
    @Mock AgentRunRepository agentRunRepository;
    @Mock VaultUploadIdempotencyCoordinator idempotencyCoordinator;
    @Mock VaultAccountIdBackfillService accountIdBackfillService;
    @Mock AccountService accountService;
    @Mock MongoTemplate mongoTemplate;
    @InjectMocks VaultService vaultService;

    private VaultDocument makeDoc(String id, Long userId) {
        return VaultDocument.builder()
                .id(id)
                .userId(userId)
                .type(VaultDocumentType.RECEIPT)
                .status(VaultDocumentStatus.ACTIVE)
                .source("manual")
                .capturedAt(Instant.now())
                .build();
    }

    // ── isolation ─────────────────────────────────────────────────────────────

    @Test
    void getById_ownDocument_returnsResponse() {
        VaultDocument doc = makeDoc("doc1", 1L);
        when(vaultDocumentRepository.findByIdAndUserId("doc1", 1L)).thenReturn(Optional.of(doc));

        VaultDocumentResponse resp = vaultService.getById(1L, "doc1");

        assertThat(resp.id()).isEqualTo("doc1");
    }

    @Test
    void getById_otherUserDocument_throwsNotFound() {
        when(vaultDocumentRepository.findByIdAndUserId("doc1", 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vaultService.getById(2L, "doc1"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_ownDocument_deletesGridFsAndDocument() {
        VaultDocument doc = makeDoc("doc1", 1L);
        doc.setGridFsFileId("gridfs-abc");
        when(vaultDocumentRepository.findByIdAndUserId("doc1", 1L)).thenReturn(Optional.of(doc));

        vaultService.delete(1L, "doc1");

        verify(gridFsFileStore).delete("gridfs-abc");
        verify(vaultDocumentRepository).deleteById("doc1");
    }

    @Test
    void delete_documentWithoutBinary_deletesDocumentOnly() {
        VaultDocument doc = makeDoc("doc2", 1L);
        when(vaultDocumentRepository.findByIdAndUserId("doc2", 1L)).thenReturn(Optional.of(doc));

        vaultService.delete(1L, "doc2");

        verify(gridFsFileStore, never()).delete(any());
        verify(vaultDocumentRepository).deleteById("doc2");
    }

    @Test
    void linkToTransaction_persistsTransactionId() {
        VaultDocument doc = makeDoc("doc3", 1L);
        when(vaultDocumentRepository.findByIdAndUserId("doc3", 1L)).thenReturn(Optional.of(doc));
        when(vaultDocumentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VaultDocumentResponse resp = vaultService.linkToTransaction(1L, "doc3", 99L);

        assertThat(resp.transactionId()).isEqualTo(99L);
    }

    // ── upload ────────────────────────────────────────────────────────────────

    @Test
    void upload_storesFileAndCreatesDocument() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("receipt.jpg");
        when(file.getBytes()).thenReturn(new byte[0]);
        when(gridFsFileStore.store(any(), eq(1L), any())).thenReturn("gridfs-xyz");
        when(vaultDocumentRepository.save(any())).thenAnswer(inv -> {
            VaultDocument d = inv.getArgument(0);
            d.setId("new-doc");
            return d;
        });
        stubCoordinatorToRunWork();

        VaultUploadOutcome<VaultDocumentResponse> outcome =
                vaultService.upload(1L, VaultDocumentType.RECEIPT, 10L, file, "test-key-0123456789");

        assertThat(outcome.replayed()).isFalse();
        assertThat(outcome.response().id()).isEqualTo("new-doc");
        assertThat(outcome.response().hasBinary()).isTrue();
        assertThat(outcome.response().accountId()).isEqualTo(10L);

        var captor = org.mockito.ArgumentCaptor.forClass(VaultDocument.class);
        verify(vaultDocumentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(VaultDocumentStatus.UPLOADED);
        assertThat(captor.getValue().getAccountId()).isEqualTo(10L);
    }

    /**
     * Stubs the mocked coordinator to actually invoke the binary-store and document-save
     * callbacks it was given, so this unit test exercises VaultService's own upload logic
     * (GridFS tagging + document construction) without depending on the coordinator's real
     * claim/replay/compensation implementation, which has its own dedicated test.
     */
    @SuppressWarnings("unchecked")
    private void stubCoordinatorToRunWork() throws IOException {
        when(idempotencyCoordinator.execute(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    VaultUploadBinaryStore binaryStore = inv.getArgument(5);
                    VaultUploadDocumentSave<Object> documentSave = inv.getArgument(6);
                    String gridFsFileId = binaryStore.storeBinary("op-1");
                    VaultUploadResult<Object> result = documentSave.saveDocument(gridFsFileId);
                    return new VaultUploadOutcome<>(result.response(), false);
                });
    }

    // ── list ──────────────────────────────────────────────────────────────────

    @Test
    void findByTransactionIds_returnsOnlyOwnedDocuments() {
        VaultDocument doc = makeDoc("doc4", 1L);
        doc.setTransactionId(42L);
        when(vaultDocumentRepository.findByTransactionIdInAndUserId(List.of(42L, 43L), 1L))
                .thenReturn(List.of(doc));

        List<VaultDocumentResponse> result = vaultService.findByTransactionIds(1L, List.of(42L, 43L));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).transactionId()).isEqualTo(42L);
    }

    @Test
    void listByAccount_scopesToAccountAndType_andTriggersBackfill() {
        VaultDocument doc = makeDoc("doc7", 1L);
        doc.setAccountId(10L);
        var pageable = PageRequest.of(0, 10);
        when(vaultDocumentRepository.findByUserIdAndAccountIdAndTypeOrderByCapturedAtDesc(
                1L, 10L, VaultDocumentType.RECEIPT, pageable))
                .thenReturn(new PageImpl<>(List.of(doc), pageable, 1));

        var page = vaultService.listByAccount(1L, 10L, VaultDocumentType.RECEIPT, pageable);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).accountId()).isEqualTo(10L);
        verify(accountIdBackfillService).ensureBackfillOnce();
    }

    @Test
    void listByType_withoutAccount_returnsOverallTypePage() {
        VaultDocument statement = makeDoc("statement-1", 1L);
        statement.setType(VaultDocumentType.STATEMENT);
        var pageable = PageRequest.of(0, 10);
        when(vaultDocumentRepository.findByUserIdAndTypeOrderByCapturedAtDesc(
                1L, VaultDocumentType.STATEMENT, pageable))
                .thenReturn(new PageImpl<>(List.of(statement), pageable, 1));

        var page = vaultService.listByType(1L, VaultDocumentType.STATEMENT, null, pageable);

        assertThat(page.getContent()).singleElement().extracting(VaultDocumentResponse::id)
                .isEqualTo("statement-1");
        verify(vaultDocumentRepository).findByUserIdAndTypeOrderByCapturedAtDesc(
                1L, VaultDocumentType.STATEMENT, pageable);
        verify(accountService, never()).findOwned(anyLong(), anyLong());
    }

    @Test
    void listByType_withAccount_validatesOwnershipAndScopesPage() {
        VaultDocument receipt = makeDoc("receipt-1", 1L);
        receipt.setAccountId(10L);
        var pageable = PageRequest.of(0, 10);
        when(vaultDocumentRepository.findByUserIdAndTypeAndAccountIdOrderByCapturedAtDesc(
                1L, VaultDocumentType.RECEIPT, 10L, pageable))
                .thenReturn(new PageImpl<>(List.of(receipt), pageable, 1));

        var page = vaultService.listByType(1L, VaultDocumentType.RECEIPT, 10L, pageable);

        assertThat(page.getContent()).singleElement().extracting(VaultDocumentResponse::accountId)
                .isEqualTo(10L);
        verify(accountService).findOwned(1L, 10L);
    }

    @Test
    void listUnassignedReceipts_surfacesLegacyAccountlessReceipts() {
        VaultDocument legacyReceipt = makeDoc("legacy-receipt", 1L);
        var pageable = PageRequest.of(0, 10);
        when(vaultDocumentRepository.findByUserIdAndTypeAndAccountIdIsNullOrderByCapturedAtDesc(
                1L, VaultDocumentType.RECEIPT, pageable))
                .thenReturn(new PageImpl<>(List.of(legacyReceipt), pageable, 1));

        var page = vaultService.listUnassignedReceipts(1L, pageable);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).id()).isEqualTo("legacy-receipt");
        assertThat(page.getContent().get(0).accountId()).isNull();
    }

    @Test
    void assignReceiptAccount_assignsOwnedLegacyReceiptToOwnedAccount() {
        VaultDocument legacyReceipt = makeDoc("legacy-receipt", 1L);
        when(vaultDocumentRepository.findByIdAndUserId("legacy-receipt", 1L))
                .thenReturn(Optional.of(legacyReceipt));
        when(mongoTemplate.findAndModify(
                any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(VaultDocument.class)))
                .thenAnswer(inv -> {
                    legacyReceipt.setAccountId(10L);
                    return legacyReceipt;
                });

        var response = vaultService.assignReceiptAccount(1L, "legacy-receipt", 10L);

        verify(accountService).findOwned(1L, 10L);
        assertThat(legacyReceipt.getAccountId()).isEqualTo(10L);
        assertThat(response.accountId()).isEqualTo(10L);
    }

    @Test
    void assignReceiptAccount_cannotMoveAlreadyAssignedReceipt() {
        VaultDocument assignedReceipt = makeDoc("assigned-receipt", 1L);
        assignedReceipt.setAccountId(20L);
        when(vaultDocumentRepository.findByIdAndUserId("assigned-receipt", 1L))
                .thenReturn(Optional.of(assignedReceipt));

        assertThatThrownBy(() -> vaultService.assignReceiptAccount(1L, "assigned-receipt", 10L))
                .isInstanceOf(com.fintrack.common.exception.ConflictException.class);

        verify(accountService).findOwned(1L, 10L);
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void search_delegatesToRepository() {
        var pageable = PageRequest.of(0, 10);
        when(vaultDocumentRepository.search(eq(1L), any(), any(), any(), any(), any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        var req = new VaultSearchRequest("amazon", null, null, null, null);
        var page = vaultService.search(1L, req, pageable);

        assertThat(page.getTotalElements()).isZero();
        verify(vaultDocumentRepository).search(eq(1L), eq("amazon"), any(), any(), any(), any(), eq(pageable));
    }

    // ── ingestion status linkage ────────────────────────────────────────────────

    private AgentRun makeRun(String vaultDocumentId, AgentRunStatus status, Instant createdAt) {
        AgentRun run = AgentRun.builder()
                .vaultDocumentId(vaultDocumentId)
                .status(status)
                .build();
        run.setCreatedAt(createdAt);
        return run;
    }

    @Test
    void list_distinguishesIngestedFromUnIngestedReceipts() {
        VaultDocument ingested = makeDoc("ingested-doc", 1L);
        VaultDocument notIngested = makeDoc("bare-doc", 1L);
        var pageable = PageRequest.of(0, 10);
        when(vaultDocumentRepository.findByUserIdOrderByCapturedAtDesc(1L, pageable))
                .thenReturn(new PageImpl<>(List.of(ingested, notIngested), pageable, 2));
        when(agentRunRepository.findByVaultDocumentIdInAndUser_Id(List.of("ingested-doc", "bare-doc"), 1L))
                .thenReturn(List.of(makeRun("ingested-doc", AgentRunStatus.COMMITTED, Instant.now())));

        var page = vaultService.list(1L, pageable);

        VaultDocumentResponse ingestedResponse = page.getContent().stream()
                .filter(r -> r.id().equals("ingested-doc")).findFirst().orElseThrow();
        VaultDocumentResponse bareResponse = page.getContent().stream()
                .filter(r -> r.id().equals("bare-doc")).findFirst().orElseThrow();

        assertThat(ingestedResponse.ingestionStatus()).isEqualTo(AgentRunStatus.COMMITTED);
        assertThat(bareResponse.ingestionStatus()).isNull();
    }

    @Test
    void list_showsLatestRunStatusWhenMultipleRunsExist() {
        VaultDocument doc = makeDoc("multi-run-doc", 1L);
        var pageable = PageRequest.of(0, 10);
        when(vaultDocumentRepository.findByUserIdOrderByCapturedAtDesc(1L, pageable))
                .thenReturn(new PageImpl<>(List.of(doc), pageable, 1));
        when(agentRunRepository.findByVaultDocumentIdInAndUser_Id(List.of("multi-run-doc"), 1L))
                .thenReturn(List.of(
                        makeRun("multi-run-doc", AgentRunStatus.FAILED, Instant.now().minusSeconds(3600)),
                        makeRun("multi-run-doc", AgentRunStatus.COMMITTED, Instant.now())));

        var page = vaultService.list(1L, pageable);

        assertThat(page.getContent().get(0).ingestionStatus()).isEqualTo(AgentRunStatus.COMMITTED);
    }

    @Test
    void list_scopesIngestionRunLookupToTheRequestingUser() {
        VaultDocument doc = makeDoc("doc5", 2L);
        var pageable = PageRequest.of(0, 10);
        when(vaultDocumentRepository.findByUserIdOrderByCapturedAtDesc(2L, pageable))
                .thenReturn(new PageImpl<>(List.of(doc), pageable, 1));
        when(agentRunRepository.findByVaultDocumentIdInAndUser_Id(any(), any())).thenReturn(List.of());

        vaultService.list(2L, pageable);

        verify(agentRunRepository).findByVaultDocumentIdInAndUser_Id(List.of("doc5"), 2L);
    }

    @Test
    void getById_exposesLatestIngestionStatusScopedToUser() {
        VaultDocument doc = makeDoc("doc6", 1L);
        when(vaultDocumentRepository.findByIdAndUserId("doc6", 1L)).thenReturn(Optional.of(doc));
        when(agentRunRepository.findFirstByVaultDocumentIdAndUser_IdOrderByCreatedAtDesc("doc6", 1L))
                .thenReturn(Optional.of(makeRun("doc6", AgentRunStatus.AWAITING_REVIEW, Instant.now())));

        VaultDocumentResponse resp = vaultService.getById(1L, "doc6");

        assertThat(resp.ingestionStatus()).isEqualTo(AgentRunStatus.AWAITING_REVIEW);
    }
}
