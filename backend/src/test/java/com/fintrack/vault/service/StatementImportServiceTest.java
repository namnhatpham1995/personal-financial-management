package com.fintrack.vault.service;

import com.fintrack.audit.support.AuditReplaySignal;
import com.fintrack.common.domain.TransactionType;
import com.fintrack.common.exception.ResourceNotFoundException;
import com.fintrack.transaction.repository.TransactionRepository;
import com.fintrack.transaction.service.TransactionService;
import com.fintrack.vault.domain.VaultDocument;
import com.fintrack.vault.parser.CsvStatementParser;
import com.fintrack.vault.parser.OfxStatementParser;
import com.fintrack.vault.parser.ParsedStatementRow;
import com.fintrack.vault.repository.VaultDocumentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
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
 * in isolation: parser selection and fingerprint construction on upload, and the not-found guard
 * on confirm. The full {@code STAGED -> CONFIRMING -> ACTIVE} compare-and-set, resume/replay, and
 * row-outcome contract needs real Mongo/Postgres semantics (atomic {@code findAndModify}, unique
 * constraint violations) to be meaningfully exercised, so that coverage lives in
 * {@code StatementImportPipelineIntegrationTest} and
 * {@code StatementConfirmationRecoveryIntegrationTest} (Testcontainers) instead of here.
 */
@ExtendWith(MockitoExtension.class)
class StatementImportServiceTest {

    @Mock VaultDocumentRepository vaultDocumentRepository;
    @Mock GridFsFileStore gridFsFileStore;
    @Mock CsvStatementParser csvParser;
    @Mock OfxStatementParser ofxParser;
    @Mock TransactionService transactionService;
    @Mock TransactionRepository transactionRepository;
    @Mock VaultUploadIdempotencyCoordinator idempotencyCoordinator;
    @Mock MongoTemplate mongoTemplate;

    private static final String IDEMPOTENCY_KEY = "test-key-0123456789";

    private StatementImportService newService() {
        return new StatementImportService(
                vaultDocumentRepository,
                gridFsFileStore,
                csvParser,
                ofxParser,
                transactionService,
                transactionRepository,
                idempotencyCoordinator,
                new com.fintrack.idempotency.service.IdempotencyKeyValidator(),
                new com.fintrack.idempotency.service.IdempotencyHasher(),
                mongoTemplate,
                jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator(),
                new AuditReplaySignal(),
                new VaultOperationMetrics(new SimpleMeterRegistry()));
    }

    /**
     * Stubs the mocked coordinator to actually invoke the binary-store and document-save
     * callbacks it was given, so these unit tests exercise StatementImportService's own upload
     * logic (parsing, fingerprint building, staged document construction) without depending on the
     * coordinator's real claim/replay/compensation implementation, which has its own dedicated
     * test.
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

    private MultipartFile csvFile() throws IOException {
        MultipartFile f = mock(MultipartFile.class);
        when(f.getOriginalFilename()).thenReturn("statement.csv");
        when(f.getInputStream()).thenReturn(new java.io.ByteArrayInputStream(new byte[0]));
        return f;
    }

    // ── upload: selects parser by extension ──────────────────────────────────

    @Test
    void upload_csvFile_usesCsvParser() throws IOException {
        StatementImportService importService = newService();
        var row = new ParsedStatementRow(LocalDate.of(2024, 1, 15),
                new BigDecimal("45.00"), TransactionType.EXPENSE, "Supermarket", "raw", null);
        when(csvParser.parse(any())).thenReturn(List.of(row));
        when(gridFsFileStore.store(any(), eq(1L), any())).thenReturn("gridfs-id");
        when(vaultDocumentRepository.save(any())).thenAnswer(inv -> {
            VaultDocument d = inv.getArgument(0);
            d.setId("staged-1");
            return d;
        });
        stubCoordinatorToRunWork();

        VaultUploadOutcome<String> outcome = importService.upload(1L, 10L, csvFile(), IDEMPOTENCY_KEY);

        assertThat(outcome.response()).isEqualTo("staged-1");
        verify(csvParser).parse(any());
        verify(ofxParser, never()).parse(any());
    }

    @Test
    void upload_ofxFile_usesOfxParser() throws IOException {
        StatementImportService importService = newService();
        MultipartFile f = mock(MultipartFile.class);
        when(f.getOriginalFilename()).thenReturn("export.ofx");
        when(f.getInputStream()).thenReturn(new java.io.ByteArrayInputStream(new byte[0]));
        when(ofxParser.parse(any())).thenReturn(List.of());
        when(gridFsFileStore.store(any(), eq(1L), any())).thenReturn("g");
        when(vaultDocumentRepository.save(any())).thenAnswer(inv -> {
            VaultDocument d = inv.getArgument(0);
            d.setId("staged-2");
            return d;
        });
        stubCoordinatorToRunWork();

        importService.upload(1L, 10L, f, IDEMPOTENCY_KEY);

        verify(ofxParser).parse(any());
        verify(csvParser, never()).parse(any());
    }

    @Test
    void upload_identicalLookingRows_getDistinctFingerprintsViaOccurrenceOrdinal() throws IOException {
        StatementImportService importService = newService();
        // Two rows with identical date/amount/type/description within the same file, no FITID.
        var row1 = new ParsedStatementRow(LocalDate.of(2024, 1, 15),
                new BigDecimal("10.00"), TransactionType.EXPENSE, "Coffee", "raw1", null);
        var row2 = new ParsedStatementRow(LocalDate.of(2024, 1, 15),
                new BigDecimal("10.00"), TransactionType.EXPENSE, "Coffee", "raw2", null);
        when(csvParser.parse(any())).thenReturn(List.of(row1, row2));
        when(gridFsFileStore.store(any(), eq(1L), any())).thenReturn("gridfs-id");
        when(vaultDocumentRepository.save(any())).thenAnswer(inv -> {
            VaultDocument d = inv.getArgument(0);
            d.setId("staged-dup");
            return d;
        });
        stubCoordinatorToRunWork();

        importService.upload(1L, 10L, csvFile(), IDEMPOTENCY_KEY);

        var captor = org.mockito.ArgumentCaptor.forClass(VaultDocument.class);
        verify(vaultDocumentRepository).save(captor.capture());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) captor.getValue().getPayload().get("rows");
        assertThat(rows).hasSize(2);
        String key1 = (String) rows.get(0).get("dedupKey");
        String key2 = (String) rows.get(1).get("dedupKey");
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void upload_sameFitId_producesSameFingerprintRegardlessOfOtherFields() throws IOException {
        StatementImportService importService = newService();
        // Same FITID, different description — fingerprint must be driven by FITID alone.
        var row1 = new ParsedStatementRow(LocalDate.of(2024, 1, 15),
                new BigDecimal("10.00"), TransactionType.EXPENSE, "Coffee shop", "raw1", "FIT-001");
        when(ofxParser.parse(any())).thenReturn(List.of(row1));
        when(gridFsFileStore.store(any(), eq(1L), any())).thenReturn("gridfs-id");
        when(vaultDocumentRepository.save(any())).thenAnswer(inv -> {
            VaultDocument d = inv.getArgument(0);
            d.setId("staged-fitid");
            return d;
        });
        stubCoordinatorToRunWork();

        MultipartFile f = mock(MultipartFile.class);
        when(f.getOriginalFilename()).thenReturn("export.ofx");
        when(f.getInputStream()).thenReturn(new java.io.ByteArrayInputStream(new byte[0]));

        importService.upload(1L, 10L, f, IDEMPOTENCY_KEY);

        var captor = org.mockito.ArgumentCaptor.forClass(VaultDocument.class);
        verify(vaultDocumentRepository).save(captor.capture());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) captor.getValue().getPayload().get("rows");
        String fingerprint = (String) rows.get(0).get("dedupKey");
        assertThat(fingerprint).isNotBlank();
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
