package com.fintrack.vault.service;

import com.fintrack.common.domain.TransactionType;
import com.fintrack.common.exception.ResourceNotFoundException;
import com.fintrack.transaction.service.TransactionService;
import com.fintrack.vault.domain.VaultDocument;
import com.fintrack.vault.domain.VaultDocumentStatus;
import com.fintrack.vault.domain.VaultDocumentType;
import com.fintrack.vault.parser.CsvStatementParser;
import com.fintrack.vault.parser.OfxStatementParser;
import com.fintrack.vault.parser.ParsedStatementRow;
import com.fintrack.vault.repository.VaultDocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StatementImportServiceTest {

    @Mock VaultDocumentRepository vaultDocumentRepository;
    @Mock GridFsFileStore gridFsFileStore;
    @Mock CsvStatementParser csvParser;
    @Mock OfxStatementParser ofxParser;
    @Mock TransactionService transactionService;
    @InjectMocks StatementImportService importService;

    private MultipartFile csvFile() throws IOException {
        MultipartFile f = mock(MultipartFile.class);
        when(f.getOriginalFilename()).thenReturn("statement.csv");
        when(f.getInputStream()).thenReturn(new java.io.ByteArrayInputStream(new byte[0]));
        return f;
    }

    // ── upload: selects parser by extension ──────────────────────────────────

    @Test
    void upload_csvFile_usesCsvParser() throws IOException {
        var row = new ParsedStatementRow(LocalDate.of(2024, 1, 15),
                new BigDecimal("45.00"), TransactionType.EXPENSE, "Supermarket", "raw");
        when(csvParser.parse(any())).thenReturn(List.of(row));
        when(gridFsFileStore.store(any(), eq(1L))).thenReturn("gridfs-id");
        when(vaultDocumentRepository.save(any())).thenAnswer(inv -> {
            VaultDocument d = inv.getArgument(0);
            d.setId("staged-1");
            return d;
        });

        String docId = importService.upload(1L, 10L, csvFile());

        assertThat(docId).isEqualTo("staged-1");
        verify(csvParser).parse(any());
        verify(ofxParser, never()).parse(any());
    }

    @Test
    void upload_ofxFile_usesOfxParser() throws IOException {
        MultipartFile f = mock(MultipartFile.class);
        when(f.getOriginalFilename()).thenReturn("export.ofx");
        when(f.getInputStream()).thenReturn(new java.io.ByteArrayInputStream(new byte[0]));
        when(ofxParser.parse(any())).thenReturn(List.of());
        when(gridFsFileStore.store(any(), eq(1L))).thenReturn("g");
        when(vaultDocumentRepository.save(any())).thenAnswer(inv -> {
            VaultDocument d = inv.getArgument(0);
            d.setId("staged-2");
            return d;
        });

        importService.upload(1L, 10L, f);

        verify(ofxParser).parse(any());
        verify(csvParser, never()).parse(any());
    }

    // ── confirm: idempotency ──────────────────────────────────────────────────

    @Test
    void confirm_duplicateDedupKey_skipsAndActivates() {
        String dedupKey = "aaaa1111";
        VaultDocument staged = VaultDocument.builder()
                .id("staged-3")
                .userId(1L)
                .type(VaultDocumentType.STATEMENT)
                .status(VaultDocumentStatus.STAGED)
                .capturedAt(Instant.now())
                .payload(Map.of("accountId", 10L, "rows", List.of(
                        Map.<String, Object>of(
                                "date", "2024-01-15",
                                "amount", "45.00",
                                "type", "EXPENSE",
                                "description", "Supermarket",
                                "dedupKey", dedupKey
                        )
                ))).build();

        when(vaultDocumentRepository.findByIdAndUserIdAndStatus("staged-3", 1L, VaultDocumentStatus.STAGED))
                .thenReturn(Optional.of(staged));
        when(transactionService.createWithImportDedupKey(eq(1L), any(), eq(dedupKey)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));
        when(vaultDocumentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int created = importService.confirm(1L, "staged-3",
                new com.fintrack.vault.web.dto.ConfirmImportRequest(List.of(dedupKey)));

        assertThat(created).isZero();
        // Document must still be activated even when all rows are duplicates
        ArgumentCaptor<VaultDocument> captor = ArgumentCaptor.forClass(VaultDocument.class);
        verify(vaultDocumentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(VaultDocumentStatus.ACTIVE);
    }

    @Test
    void confirm_newRows_createsTransactionsAndActivates() {
        String key1 = "bbbb2222";
        String key2 = "cccc3333";
        VaultDocument staged = VaultDocument.builder()
                .id("staged-4")
                .userId(1L)
                .type(VaultDocumentType.STATEMENT)
                .status(VaultDocumentStatus.STAGED)
                .capturedAt(Instant.now())
                .payload(Map.of("accountId", 10L, "rows", List.of(
                        Map.<String, Object>of("date", "2024-01-10", "amount", "100.00",
                                "type", "INCOME", "description", "Salary", "dedupKey", key1),
                        Map.<String, Object>of("date", "2024-01-12", "amount", "20.00",
                                "type", "EXPENSE", "description", "Coffee", "dedupKey", key2)
                ))).build();

        when(vaultDocumentRepository.findByIdAndUserIdAndStatus("staged-4", 1L, VaultDocumentStatus.STAGED))
                .thenReturn(Optional.of(staged));
        when(transactionService.createWithImportDedupKey(eq(1L), any(), any())).thenReturn(null);
        when(vaultDocumentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int created = importService.confirm(1L, "staged-4",
                new com.fintrack.vault.web.dto.ConfirmImportRequest(List.of(key1, key2)));

        assertThat(created).isEqualTo(2);
        verify(transactionService).createWithImportDedupKey(eq(1L), any(), eq(key1));
        verify(transactionService).createWithImportDedupKey(eq(1L), any(), eq(key2));
    }

    @Test
    void confirm_stagedDocumentNotFound_throwsNotFound() {
        when(vaultDocumentRepository.findByIdAndUserIdAndStatus(any(), eq(1L), eq(VaultDocumentStatus.STAGED)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> importService.confirm(1L, "missing",
                new com.fintrack.vault.web.dto.ConfirmImportRequest(List.of())))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
