package com.fintrack.vault.service;

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
        when(gridFsFileStore.store(any(), eq(1L))).thenReturn("gridfs-xyz");
        when(vaultDocumentRepository.save(any())).thenAnswer(inv -> {
            VaultDocument d = inv.getArgument(0);
            d.setId("new-doc");
            return d;
        });

        VaultDocumentResponse resp = vaultService.upload(1L, VaultDocumentType.RECEIPT, file);

        assertThat(resp.id()).isEqualTo("new-doc");
        assertThat(resp.hasBinary()).isTrue();
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
    void search_delegatesToRepository() {
        var pageable = PageRequest.of(0, 10);
        when(vaultDocumentRepository.search(eq(1L), any(), any(), any(), any(), any(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        var req = new VaultSearchRequest("amazon", null, null, null, null);
        var page = vaultService.search(1L, req, pageable);

        assertThat(page.getTotalElements()).isZero();
        verify(vaultDocumentRepository).search(eq(1L), eq("amazon"), any(), any(), any(), any(), eq(pageable));
    }
}
