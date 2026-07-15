package com.fintrack.vault.service;

import com.fintrack.common.exception.ResourceNotFoundException;
import com.fintrack.transaction.service.TransactionService;
import com.fintrack.transaction.web.dto.CreateTransactionRequest;
import com.fintrack.vault.domain.VaultDocument;
import com.fintrack.vault.domain.VaultDocumentStatus;
import com.fintrack.vault.domain.VaultDocumentType;
import com.fintrack.vault.parser.CsvStatementParser;
import com.fintrack.vault.parser.OfxStatementParser;
import com.fintrack.vault.parser.ParsedStatementRow;
import com.fintrack.vault.repository.VaultDocumentRepository;
import com.fintrack.vault.web.dto.StagedRowResponse;
import com.fintrack.vault.web.dto.ConfirmImportRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatementImportService {

    private final VaultDocumentRepository vaultDocumentRepository;
    private final GridFsFileStore gridFsFileStore;
    private final CsvStatementParser csvParser;
    private final OfxStatementParser ofxParser;
    private final TransactionService transactionService;

    /**
     * Parses the uploaded file, stores the binary in GridFS, and creates a STAGED
     * VaultDocument whose payload holds the parsed rows for user review.
     * Returns the vault document id so the caller can fetch staged rows.
     */
    public String upload(Long userId, Long accountId, MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename() != null
                ? file.getOriginalFilename().toLowerCase() : "";

        List<ParsedStatementRow> rows;
        String source;
        if (filename.endsWith(".ofx") || filename.endsWith(".qfx")) {
            rows = ofxParser.parse(file.getInputStream());
            source = "ofx";
        } else {
            rows = csvParser.parse(file.getInputStream());
            source = "csv";
        }

        String fileId = gridFsFileStore.store(file, userId);

        // Serialize rows into a JSON-compatible list of maps for the payload
        List<Map<String, Object>> rowMaps = rows.stream()
                .map(r -> Map.<String, Object>of(
                        "date", r.date().toString(),
                        "amount", r.amount(),
                        "type", r.type().name(),
                        "description", r.description(),
                        "dedupKey", buildDedupKey(userId, accountId, r)
                ))
                .toList();

        VaultDocument staged = VaultDocument.builder()
                .userId(userId)
                .type(VaultDocumentType.STATEMENT)
                .status(VaultDocumentStatus.STAGED)
                .source(source)
                .capturedAt(Instant.now())
                .gridFsFileId(fileId)
                .originalFilename(file.getOriginalFilename())
                .payload(Map.of("accountId", accountId, "rows", rowMaps))
                .build();

        return vaultDocumentRepository.save(staged).getId();
    }

    /** Returns the staged rows for user review before confirmation. */
    @SuppressWarnings("unchecked")
    public List<StagedRowResponse> getReviewRows(Long userId, String documentId) {
        VaultDocument doc = vaultDocumentRepository
                .findByIdAndUserIdAndStatus(documentId, userId, VaultDocumentStatus.STAGED)
                .orElseThrow(() -> ResourceNotFoundException.of("StagedStatement", documentId));

        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) doc.getPayload().get("rows");

        return rows.stream()
                .map(r -> new StagedRowResponse(
                        (String) r.get("date"),
                        // MongoDB round-trips this untyped map value as a String, not a Number
                        // (matches the .toString() handling already used in confirm() below).
                        r.get("amount").toString(),
                        (String) r.get("type"),
                        (String) r.get("description"),
                        (String) r.get("dedupKey")
                ))
                .toList();
    }

    /**
     * Confirms selected rows: creates a PostgreSQL transaction for each row whose
     * dedupKey is not already present, then marks the vault document ACTIVE.
     * Rows with an existing dedupKey are silently skipped (idempotent re-import).
     */
    @SuppressWarnings("unchecked")
    public int confirm(Long userId, String documentId, ConfirmImportRequest req) {
        VaultDocument doc = vaultDocumentRepository
                .findByIdAndUserIdAndStatus(documentId, userId, VaultDocumentStatus.STAGED)
                .orElseThrow(() -> ResourceNotFoundException.of("StagedStatement", documentId));

        Long accountId = ((Number) doc.getPayload().get("accountId")).longValue();
        List<Map<String, Object>> allRows =
                (List<Map<String, Object>>) doc.getPayload().get("rows");

        // Keep only rows the user selected
        List<Map<String, Object>> selected = allRows.stream()
                .filter(r -> req.selectedDedupKeys().contains((String) r.get("dedupKey")))
                .toList();

        int created = 0;
        List<String> failedKeys = new ArrayList<>();
        for (Map<String, Object> row : selected) {
            String dedupKey = (String) row.get("dedupKey");
            try {
                var txReq = new CreateTransactionRequest(
                        com.fintrack.common.domain.TransactionType.valueOf((String) row.get("type")),
                        new java.math.BigDecimal(row.get("amount").toString()),
                        java.time.LocalDate.parse((String) row.get("date")),
                        accountId,
                        null,
                        null,
                        null,
                        (String) row.get("description"),
                        dedupKey   // importDedupKey — prevents duplicate on re-import
                );
                transactionService.create(userId, txReq);
                created++;
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // Unique constraint on import_dedup_key — already imported, skip
                log.debug("Skipping duplicate row with dedup key {}", dedupKey);
            }
        }

        // Activate regardless — even if all rows were duplicates the upload was successful
        doc.setStatus(VaultDocumentStatus.ACTIVE);
        vaultDocumentRepository.save(doc);

        return created;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String buildDedupKey(Long userId, Long accountId, ParsedStatementRow row) {
        String raw = userId + "|" + accountId + "|"
                + row.date() + "|"
                + row.amount().toPlainString() + "|"
                + row.description().strip().toLowerCase();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
