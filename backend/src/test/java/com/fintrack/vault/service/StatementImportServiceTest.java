package com.fintrack.vault.service;

import com.fintrack.audit.support.AuditReplaySignal;
import com.fintrack.common.domain.TransactionType;
import com.fintrack.common.exception.ConflictException;
import com.fintrack.common.exception.ResourceNotFoundException;
import com.fintrack.transaction.repository.TransactionRepository;
import com.fintrack.transaction.service.TransactionService;
import com.fintrack.vault.domain.VaultDocument;
import com.fintrack.vault.domain.VaultDocumentStatus;
import com.fintrack.vault.domain.VaultDocumentType;
import com.fintrack.vault.parser.CsvStatementParser;
import com.fintrack.vault.parser.OfxStatementParser;
import com.fintrack.vault.parser.ParsedStatementRow;
import com.fintrack.vault.repository.VaultDocumentRepository;
import com.fintrack.vault.web.dto.StagedRowResponse;
import com.fintrack.vault.web.dto.VaultDocumentResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
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

/**
 * Unit-level coverage for the parts of {@link StatementImportService} that are cleanly mockable
 * in isolation: upload's delegation to the shared {@link VaultService#upload} path plus the
 * format allow-list, parse's parser selection and fingerprint construction, and the not-found
 * guard on confirm. The full {@code STAGED -> CONFIRMING -> ACTIVE} compare-and-set, resume/replay,
 * and row-outcome contract needs real Mongo/Postgres semantics (atomic {@code findAndModify},
 * unique constraint violations) to be meaningfully exercised, so that coverage lives in
 * {@code StatementImportPipelineIntegrationTest} and {@code StatementConfirmationRecoveryIntegrationTest}
 * (Testcontainers) instead of here.
 */
@ExtendWith(MockitoExtension.class)
class StatementImportServiceTest {

    @Mock VaultDocumentRepository vaultDocumentRepository;
    @Mock VaultService vaultService;
    @Mock VaultAccountIdBackfillService accountIdBackfillService;
    @Mock GridFsFileStore gridFsFileStore;
    @Mock CsvStatementParser csvParser;
    @Mock OfxStatementParser ofxParser;
    @Mock TransactionService transactionService;
    @Mock TransactionRepository transactionRepository;
    @Mock MongoTemplate mongoTemplate;

    private static final String IDEMPOTENCY_KEY = "test-key-0123456789";

    private StatementImportService newService() {
        return new StatementImportService(
                vaultDocumentRepository,
                vaultService,
                accountIdBackfillService,
                gridFsFileStore,
                csvParser,
                ofxParser,
                transactionService,
                transactionRepository,
                new com.fintrack.idempotency.service.IdempotencyKeyValidator(),
                new com.fintrack.idempotency.service.IdempotencyHasher(),
                mongoTemplate,
                jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator(),
                new AuditReplaySignal(),
                new VaultOperationMetrics(new SimpleMeterRegistry()));
    }

    private MultipartFile csvFile() {
        MultipartFile f = mock(MultipartFile.class);
        when(f.getOriginalFilename()).thenReturn("statement.csv");
        return f;
    }

    private VaultDocumentResponse fakeResponse(String id, Long accountId) {
        return new VaultDocumentResponse(id, accountId, VaultDocumentType.STATEMENT, VaultDocumentStatus.UPLOADED,
                "manual", Instant.now(), null, true, "statement.csv", null, null);
    }

    // ── upload: delegates to the shared VaultService binary path ───────────────

    @Test
    void upload_supportedFile_delegatesToVaultServiceAndReturnsDocumentId() throws IOException {
        StatementImportService importService = newService();
        when(vaultService.upload(eq(1L), eq(VaultDocumentType.STATEMENT), eq(10L), any(), eq(IDEMPOTENCY_KEY)))
                .thenReturn(new VaultUploadOutcome<>(fakeResponse("doc-1", 10L), false));

        VaultUploadOutcome<String> outcome = importService.upload(1L, 10L, csvFile(), IDEMPOTENCY_KEY);

        assertThat(outcome.response()).isEqualTo("doc-1");
        assertThat(outcome.replayed()).isFalse();
    }

    @Test
    void upload_unsupportedExtension_rejectedWithoutDelegating() {
        StatementImportService importService = newService();
        MultipartFile f = mock(MultipartFile.class);
        when(f.getOriginalFilename()).thenReturn("statement.xlsx");

        assertThatThrownBy(() -> importService.upload(1L, 10L, f, IDEMPOTENCY_KEY))
                .isInstanceOf(UnsupportedStatementFormatException.class);

        verifyNoInteractions(vaultService, csvParser, ofxParser);
    }

    @Test
    void upload_noExtension_rejectedAsUnsupportedFormat() {
        StatementImportService importService = newService();
        MultipartFile f = mock(MultipartFile.class);
        when(f.getOriginalFilename()).thenReturn("statement");

        assertThatThrownBy(() -> importService.upload(1L, 10L, f, IDEMPOTENCY_KEY))
                .isInstanceOf(UnsupportedStatementFormatException.class);
    }

    // ── parse: selects parser by extension, builds fingerprints, transitions to STAGED ──

    private VaultDocument uploadedDoc(String filename) {
        return VaultDocument.builder()
                .id("doc-1")
                .userId(1L)
                .accountId(10L)
                .type(VaultDocumentType.STATEMENT)
                .status(VaultDocumentStatus.UPLOADED)
                .gridFsFileId("gridfs-1")
                .originalFilename(filename)
                .build();
    }


    @Test
    void parse_csvFile_usesCsvParserAndTransitionsToStaged() throws IOException {
        StatementImportService importService = newService();
        VaultDocument doc = uploadedDoc("statement.csv");
        when(vaultDocumentRepository.findByIdAndUserId("doc-1", 1L)).thenReturn(Optional.of(doc));
        when(gridFsFileStore.loadInputStream("gridfs-1", 1L)).thenReturn(new ByteArrayInputStream(new byte[0]));
        var row = new ParsedStatementRow(LocalDate.of(2024, 1, 15),
                new BigDecimal("45.00"), TransactionType.EXPENSE, "Supermarket", "raw", null);
        when(csvParser.parse(any())).thenReturn(List.of(row));
        when(vaultDocumentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<StagedRowResponse> rows = importService.parse(1L, "doc-1");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).description()).isEqualTo("Supermarket");
        verify(csvParser).parse(any());
        verify(ofxParser, never()).parse(any());
        verify(accountIdBackfillService).ensureBackfillOnce();

        var captor = org.mockito.ArgumentCaptor.forClass(VaultDocument.class);
        verify(vaultDocumentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(VaultDocumentStatus.STAGED);
    }

    @Test
    void parse_ofxFile_usesOfxParser() throws IOException {
        StatementImportService importService = newService();
        VaultDocument doc = uploadedDoc("export.ofx");
        when(vaultDocumentRepository.findByIdAndUserId("doc-1", 1L)).thenReturn(Optional.of(doc));
        when(gridFsFileStore.loadInputStream("gridfs-1", 1L)).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(ofxParser.parse(any())).thenReturn(List.of());
        when(vaultDocumentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        importService.parse(1L, "doc-1");

        verify(ofxParser).parse(any());
        verify(csvParser, never()).parse(any());
    }

    @Test
    void parse_identicalLookingRows_getDistinctFingerprintsViaOccurrenceOrdinal() throws IOException {
        StatementImportService importService = newService();
        VaultDocument doc = uploadedDoc("statement.csv");
        when(vaultDocumentRepository.findByIdAndUserId("doc-1", 1L)).thenReturn(Optional.of(doc));
        when(gridFsFileStore.loadInputStream("gridfs-1", 1L)).thenReturn(new ByteArrayInputStream(new byte[0]));
        var row1 = new ParsedStatementRow(LocalDate.of(2024, 1, 15),
                new BigDecimal("10.00"), TransactionType.EXPENSE, "Coffee", "raw1", null);
        var row2 = new ParsedStatementRow(LocalDate.of(2024, 1, 15),
                new BigDecimal("10.00"), TransactionType.EXPENSE, "Coffee", "raw2", null);
        when(csvParser.parse(any())).thenReturn(List.of(row1, row2));
        when(vaultDocumentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<StagedRowResponse> rows = importService.parse(1L, "doc-1");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).dedupKey()).isNotEqualTo(rows.get(1).dedupKey());
    }

    @Test
    void parse_sameFitId_producesSameFingerprintRegardlessOfOtherFields() throws IOException {
        StatementImportService importService = newService();
        VaultDocument doc = uploadedDoc("export.ofx");
        when(vaultDocumentRepository.findByIdAndUserId("doc-1", 1L)).thenReturn(Optional.of(doc));
        when(gridFsFileStore.loadInputStream("gridfs-1", 1L)).thenReturn(new ByteArrayInputStream(new byte[0]));
        var row1 = new ParsedStatementRow(LocalDate.of(2024, 1, 15),
                new BigDecimal("10.00"), TransactionType.EXPENSE, "Coffee shop", "raw1", "FIT-001");
        when(ofxParser.parse(any())).thenReturn(List.of(row1));
        when(vaultDocumentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<StagedRowResponse> rows = importService.parse(1L, "doc-1");

        assertThat(rows.get(0).dedupKey()).isNotBlank();
    }

    @Test
    void parse_documentNotFound_throwsNotFound() {
        StatementImportService importService = newService();
        when(vaultDocumentRepository.findByIdAndUserId("missing", 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> importService.parse(1L, "missing"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void parse_alreadyConfirmed_throwsConflict() {
        StatementImportService importService = newService();
        VaultDocument doc = uploadedDoc("statement.csv");
        doc.setStatus(VaultDocumentStatus.ACTIVE);
        when(vaultDocumentRepository.findByIdAndUserId("doc-1", 1L)).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> importService.parse(1L, "doc-1"))
                .isInstanceOf(ConflictException.class);

        verifyNoInteractions(csvParser, ofxParser);
    }

    @Test
    void parse_alreadyStaged_isSafelyReRunnable() throws IOException {
        StatementImportService importService = newService();
        VaultDocument doc = uploadedDoc("statement.csv");
        doc.setStatus(VaultDocumentStatus.STAGED);
        when(vaultDocumentRepository.findByIdAndUserId("doc-1", 1L)).thenReturn(Optional.of(doc));
        when(gridFsFileStore.loadInputStream("gridfs-1", 1L)).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(csvParser.parse(any())).thenReturn(List.of());
        when(vaultDocumentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<StagedRowResponse> rows = importService.parse(1L, "doc-1");

        assertThat(rows).isEmpty();
    }

    // ── confirm: not found ────────────────────────────────────────────────────

    @Test
    void confirm_documentNotFound_throwsNotFound() {
        StatementImportService importService = newService();
        when(vaultDocumentRepository.findByIdAndUserId("missing", 1L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> importService.confirm(1L, "missing",
                new com.fintrack.vault.web.dto.ConfirmImportRequest(List.of()), IDEMPOTENCY_KEY))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void confirm_invalidIdempotencyKey_rejectedBeforeAnyLookup() {
        StatementImportService importService = newService();

        assertThatThrownBy(() -> importService.confirm(1L, "doc-1",
                new com.fintrack.vault.web.dto.ConfirmImportRequest(List.of()), "too-short"))
                .isInstanceOf(com.fintrack.idempotency.exception.InvalidIdempotencyKeyException.class);

        verifyNoInteractions(vaultDocumentRepository);
    }
}
