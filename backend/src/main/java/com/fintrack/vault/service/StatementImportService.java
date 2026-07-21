package com.fintrack.vault.service;

import com.fintrack.audit.support.AuditReplaySignal;
import com.fintrack.common.domain.TransactionType;
import com.fintrack.common.exception.ResourceNotFoundException;
import com.fintrack.idempotency.exception.IdempotencyConflictException;
import com.fintrack.idempotency.service.IdempotencyHasher;
import com.fintrack.idempotency.service.IdempotencyKeyValidator;
import com.fintrack.transaction.domain.Transaction;
import com.fintrack.transaction.repository.TransactionRepository;
import com.fintrack.transaction.service.TransactionService;
import com.fintrack.transaction.web.dto.CreateTransactionRequest;
import com.fintrack.transaction.web.dto.TransactionResponse;
import com.fintrack.vault.domain.RowOutcome;
import com.fintrack.vault.domain.RowOutcomeStatus;
import com.fintrack.vault.domain.VaultDocument;
import com.fintrack.vault.domain.VaultDocumentStatus;
import com.fintrack.vault.domain.VaultDocumentType;
import com.fintrack.vault.parser.CsvStatementParser;
import com.fintrack.vault.parser.OfxStatementParser;
import com.fintrack.vault.parser.ParsedStatementRow;
import com.fintrack.vault.repository.VaultDocumentRepository;
import com.fintrack.vault.web.dto.ConfirmImportRequest;
import com.fintrack.vault.web.dto.ConfirmImportResponse;
import com.fintrack.vault.web.dto.StagedRowResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatementImportService {

    private static final String UPLOAD_OPERATION_NAME = "statement.upload";
    private static final String CONFIRM_OPERATION_NAME = "statement.confirm";

    /** Constraint name from V15/V16 — see {@link #isDedupConstraintViolation}. */
    private static final String DEDUP_CONSTRAINT_NAME = "uq_transactions_user_import_dedup_key";

    /** Mirrors the poll bound/interval used by the other idempotency coordinators in this series. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(150);
    private static final Duration POLL_BOUND = Duration.ofSeconds(3);

    private final VaultDocumentRepository vaultDocumentRepository;
    private final GridFsFileStore gridFsFileStore;
    private final CsvStatementParser csvParser;
    private final OfxStatementParser ofxParser;
    private final TransactionService transactionService;
    private final TransactionRepository transactionRepository;
    private final VaultUploadIdempotencyCoordinator idempotencyCoordinator;
    private final IdempotencyKeyValidator keyValidator;
    private final IdempotencyHasher hasher;
    private final MongoTemplate mongoTemplate;
    private final Validator beanValidator;
    private final AuditReplaySignal auditReplaySignal;
    private final VaultOperationMetrics metrics;

    /**
     * Parses the uploaded file, stores the binary in GridFS, and creates a STAGED
     * VaultDocument whose payload holds the parsed rows for user review.
     * Returns the vault document id so the caller can fetch staged rows.
     *
     * <p>Requires an {@code Idempotency-Key} (per the statement-import spec, uploads SHALL
     * require one) — same-key/same-account/same-file retries replay the original staged document
     * id without a second binary or staged document; same-key/different-file (or account)
     * retries return a typed 409. Parsing happens before the binary is even claimed, so a
     * malformed file fails fast without touching GridFS or Mongo.
     */
    public VaultUploadOutcome<String> upload(Long userId, Long accountId, MultipartFile file,
                                              String rawIdempotencyKey) throws IOException {
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

        List<Map<String, Object>> rowMaps = buildRowMaps(userId, accountId, rows);

        Map<String, String> nonFileParams = Map.of("accountId", String.valueOf(accountId));
        byte[] fileBytes = file.getBytes();

        return idempotencyCoordinator.execute(
                userId,
                UPLOAD_OPERATION_NAME,
                rawIdempotencyKey,
                nonFileParams,
                fileBytes,
                operationId -> gridFsFileStore.store(file, userId, operationId),
                gridFsFileId -> {
                    VaultDocument staged = VaultDocument.builder()
                            .userId(userId)
                            .type(VaultDocumentType.STATEMENT)
                            .status(VaultDocumentStatus.STAGED)
                            .source(source)
                            .capturedAt(Instant.now())
                            .gridFsFileId(gridFsFileId)
                            .originalFilename(file.getOriginalFilename())
                            .payload(Map.of("accountId", accountId, "rows", rowMaps))
                            .build();
                    String documentId = vaultDocumentRepository.save(staged).getId();
                    return new VaultUploadResult<>(documentId, documentId);
                },
                vaultDocumentId -> vaultDocumentId);
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
     * Confirms selected rows: atomically claims a {@code STAGED -> CONFIRMING} transition keyed
     * by the presented Idempotency-Key and canonical selected-row set, normalizes every selected
     * row into a PostgreSQL transaction (or a durable duplicate/failed outcome), then transitions
     * {@code CONFIRMING -> ACTIVE}. See the "Confirmed rows are normalized into PostgreSQL
     * transactions idempotently" requirement in
     * {@code openspec/changes/harden-idempotent-mutations/specs/statement-import/spec.md} for the
     * exact resume/replay/conflict contract this implements.
     */
    public ConfirmImportResponse confirm(Long userId, String documentId, ConfirmImportRequest req,
                                          String rawIdempotencyKey) {
        keyValidator.validate(rawIdempotencyKey);
        String keyHash = hasher.hashKey(rawIdempotencyKey);
        String requestHash = hasher.hashJsonRequest(CONFIRM_OPERATION_NAME, canonicalSelection(req.selectedDedupKeys()));

        VaultDocument doc = vaultDocumentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> ResourceNotFoundException.of("StagedStatement", documentId));

        return resolveConfirmation(doc, userId, documentId, req, keyHash, requestHash);
    }

    // ── confirm: CAS state machine ──────────────────────────────────────────────

    private ConfirmImportResponse resolveConfirmation(VaultDocument doc, Long userId, String documentId,
                                                        ConfirmImportRequest req, String keyHash, String requestHash) {
        return switch (doc.getStatus()) {
            case STAGED -> {
                VaultDocument claimed = tryClaimConfirmation(documentId, userId, keyHash, requestHash);
                if (claimed != null) {
                    metrics.claimed(CONFIRM_OPERATION_NAME);
                    log.debug("Vault operation claim won: operation={}", CONFIRM_OPERATION_NAME);
                    ConfirmImportResponse response = runAndActivate(claimed, req);
                    // Original confirmation request (fresh claim) — attach the vault document id
                    // itself as the non-secret correlation reference; there is no separate
                    // operation row for statement confirmation.
                    auditReplaySignal.setOperationReference(documentId);
                    yield response;
                }
                // Lost the CAS race to a concurrent request — re-fetch and fall through to
                // whatever state won (CONFIRMING or, if it already finished, ACTIVE).
                VaultDocument refreshed = vaultDocumentRepository.findByIdAndUserId(documentId, userId)
                        .orElseThrow(() -> ResourceNotFoundException.of("StagedStatement", documentId));
                yield resolveConfirmation(refreshed, userId, documentId, req, keyHash, requestHash);
            }
            case CONFIRMING -> {
                requireMatchingConfirmation(doc, keyHash, requestHash);
                // Resuming a partially-processed confirmation: at least the rows that had not yet
                // reached a terminal outcome do real work here, so this is treated as an original
                // request (not a pure replay) even though already-decided rows resolve as cheap
                // per-row resume lookups within processRows().
                ConfirmImportResponse response = runAndActivate(doc, req);
                auditReplaySignal.setOperationReference(documentId);
                yield response;
            }
            case ACTIVE -> {
                requireMatchingConfirmation(doc, keyHash, requestHash);
                // Same key, same selected-row set, already fully activated: pure replay — no row
                // was (re)processed, so the interceptor must not record a second audit event.
                metrics.replayed(CONFIRM_OPERATION_NAME);
                log.debug("Vault operation replay: operation={}", CONFIRM_OPERATION_NAME);
                auditReplaySignal.markReplayed();
                yield buildResponse(doc, req.selectedDedupKeys());
            }
        };
    }

    private void requireMatchingConfirmation(VaultDocument doc, String keyHash, String requestHash) {
        boolean matches = keyHash.equals(doc.getConfirmationKeyHash())
                && requestHash.equals(doc.getConfirmationRequestHash());
        if (!matches) {
            metrics.conflicted(CONFIRM_OPERATION_NAME);
            log.warn("Vault operation payload conflict: operation={}", CONFIRM_OPERATION_NAME);
            String stage = doc.getStatus() == VaultDocumentStatus.ACTIVE ? "already completed" : "already in progress";
            throw new IdempotencyConflictException(
                    "Statement confirmation is " + stage + " for this document with a different "
                            + "Idempotency-Key or selected-row set");
        }
    }

    private VaultDocument tryClaimConfirmation(String documentId, Long userId, String keyHash, String requestHash) {
        Query query = Query.query(Criteria.where("_id").is(documentId)
                .and("userId").is(userId)
                .and("status").is(VaultDocumentStatus.STAGED));
        Update update = new Update()
                .set("status", VaultDocumentStatus.CONFIRMING)
                .set("confirmationKeyHash", keyHash)
                .set("confirmationRequestHash", requestHash)
                .set("confirmationStartedAt", Instant.now())
                .set("confirmationCompletedAt", null)
                .set("confirmationRowOutcomes", new HashMap<String, RowOutcome>());
        return mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), VaultDocument.class);
    }

    /** Processes every selected row (resuming already-decided ones), then flips CONFIRMING -> ACTIVE. */
    private ConfirmImportResponse runAndActivate(VaultDocument doc, ConfirmImportRequest req) {
        processRows(doc, req);

        Query query = Query.query(Criteria.where("_id").is(doc.getId())
                .and("status").is(VaultDocumentStatus.CONFIRMING));
        Update update = new Update()
                .set("status", VaultDocumentStatus.ACTIVE)
                .set("confirmationCompletedAt", Instant.now());
        VaultDocument activated = mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), VaultDocument.class);

        VaultDocument finalDoc = activated != null
                ? activated
                : vaultDocumentRepository.findByIdAndUserId(doc.getId(), doc.getUserId()).orElse(doc);
        return buildResponse(finalDoc, req.selectedDedupKeys());
    }

    @SuppressWarnings("unchecked")
    private void processRows(VaultDocument doc, ConfirmImportRequest req) {
        Long accountId = ((Number) doc.getPayload().get("accountId")).longValue();
        List<Map<String, Object>> allRows = (List<Map<String, Object>>) doc.getPayload().get("rows");
        Map<String, Map<String, Object>> rowsByKey = allRows.stream()
                .collect(Collectors.toMap(r -> (String) r.get("dedupKey"), r -> r, (a, b) -> a));

        for (String dedupKey : req.selectedDedupKeys()) {
            claimAndProcessRow(doc.getId(), doc.getUserId(), accountId, rowsByKey.get(dedupKey), dedupKey);
        }
    }

    /**
     * Atomically claims one row (so two concurrent confirmation attempts on the same document
     * never both create a transaction for it), processes it if the claim was won, and durably
     * records the terminal outcome. If the claim is lost — the row already has an outcome from an
     * earlier attempt (resume) or is being processed concurrently right now (race) — waits for the
     * terminal outcome instead of reprocessing.
     */
    private RowOutcome claimAndProcessRow(String documentId, Long userId, Long accountId,
                                           Map<String, Object> row, String dedupKey) {
        String outcomeField = "confirmationRowOutcomes." + dedupKey;
        Query claimQuery = Query.query(Criteria.where("_id").is(documentId).and(outcomeField).exists(false));
        Update claimUpdate = Update.update(outcomeField, RowOutcome.processing());

        VaultDocument claimedDoc = mongoTemplate.findAndModify(claimQuery, claimUpdate,
                FindAndModifyOptions.options().returnNew(false), VaultDocument.class);

        if (claimedDoc == null) {
            return awaitRowOutcome(documentId, dedupKey);
        }

        RowOutcome outcome = processOneRow(userId, documentId, accountId, row, dedupKey);
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(documentId)),
                Update.update(outcomeField, outcome), VaultDocument.class);
        return outcome;
    }

    private RowOutcome awaitRowOutcome(String documentId, String dedupKey) {
        Instant deadline = Instant.now().plus(POLL_BOUND);
        while (true) {
            VaultDocument doc = vaultDocumentRepository.findById(documentId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Vault document " + documentId + " vanished during statement confirmation"));
            Map<String, RowOutcome> outcomes = doc.getConfirmationRowOutcomes();
            RowOutcome outcome = outcomes == null ? null : outcomes.get(dedupKey);
            if (outcome != null && outcome.getStatus() != RowOutcomeStatus.PROCESSING) {
                return outcome;
            }
            if (Instant.now().isAfter(deadline)) {
                log.warn("Row {} on statement document {} stayed PROCESSING past the poll bound; "
                        + "reporting failed rather than blocking the confirmation response", dedupKey, documentId);
                return RowOutcome.failed("Row confirmation timed out waiting for a concurrent attempt to finish");
            }
            sleepPollInterval();
        }
    }

    /**
     * Validates and normalizes one selected row into a PostgreSQL transaction. Only the targeted
     * user-scoped dedup constraint is treated as a duplicate — every other failure (validation,
     * missing row, foreign key, any other integrity violation) is reported as failed, per the
     * "Non-duplicate integrity failure is reported" scenario.
     */
    private RowOutcome processOneRow(Long userId, String documentId, Long accountId,
                                      Map<String, Object> row, String dedupKey) {
        if (row == null) {
            return RowOutcome.failed("Selected row was not found in the staged document");
        }
        try {
            CreateTransactionRequest txReq = new CreateTransactionRequest(
                    TransactionType.valueOf((String) row.get("type")),
                    new BigDecimal(row.get("amount").toString()),
                    LocalDate.parse((String) row.get("date")),
                    accountId,
                    null,
                    null,
                    null,
                    (String) row.get("description"));

            Set<ConstraintViolation<CreateTransactionRequest>> violations = beanValidator.validate(txReq);
            if (!violations.isEmpty()) {
                return RowOutcome.failed(violations.iterator().next().getMessage());
            }

            TransactionResponse created;
            try {
                // dedupKey (import_dedup_key) — the sole signal for re-import dedup; passed
                // separately since CreateTransactionRequest carries no import fingerprint field.
                created = transactionService.createWithImportDedupKey(userId, txReq, dedupKey);
            } catch (DataIntegrityViolationException e) {
                if (isDedupConstraintViolation(e)) {
                    log.debug("Skipping duplicate row with dedup key {}", dedupKey);
                    return RowOutcome.duplicate();
                }
                return RowOutcome.failed(rootMessage(e));
            }

            linkSourceDocument(created.id(), documentId);
            return RowOutcome.created(created.id());
        } catch (RuntimeException e) {
            return RowOutcome.failed(rootMessage(e));
        }
    }

    /**
     * Only the named composite dedup index counts as "already imported" — any other integrity
     * violation (foreign key, check constraint, a different unique index) must surface as a
     * genuine failure instead of being mislabeled as a duplicate.
     */
    private boolean isDedupConstraintViolation(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        String message = cause == null ? null : cause.getMessage();
        return message != null && message.contains(DEDUP_CONSTRAINT_NAME);
    }

    private String rootMessage(RuntimeException e) {
        Throwable cause = e instanceof DataIntegrityViolationException div ? div.getMostSpecificCause() : e;
        String message = cause == null ? null : cause.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /**
     * {@code TransactionService.createWithImportDedupKey} has no source-document parameter (it is
     * shared, unmodified idempotency infrastructure), so the link is recorded with a direct
     * follow-up save instead.
     */
    private void linkSourceDocument(Long transactionId, String documentId) {
        transactionRepository.findById(transactionId).ifPresent(t -> {
            t.setSourceDocumentId(documentId);
            transactionRepository.save(t);
        });
    }

    private ConfirmImportResponse buildResponse(VaultDocument doc, List<String> selectedDedupKeys) {
        Map<String, RowOutcome> outcomes = doc.getConfirmationRowOutcomes() == null
                ? Map.of() : doc.getConfirmationRowOutcomes();

        int created = 0;
        int duplicate = 0;
        int failed = 0;
        List<ConfirmImportResponse.RowResult> rows = new ArrayList<>();
        for (String dedupKey : selectedDedupKeys) {
            RowOutcome outcome = outcomes.get(dedupKey);
            if (outcome == null || outcome.getStatus() == RowOutcomeStatus.PROCESSING) {
                failed++;
                rows.add(new ConfirmImportResponse.RowResult(dedupKey, "FAILED", null,
                        "Row outcome was not recorded"));
                continue;
            }
            switch (outcome.getStatus()) {
                case CREATED -> created++;
                case DUPLICATE -> duplicate++;
                case FAILED -> failed++;
                case PROCESSING -> { /* unreachable, handled above */ }
            }
            rows.add(new ConfirmImportResponse.RowResult(
                    dedupKey, outcome.getStatus().name(), outcome.getTransactionId(), outcome.getError()));
        }
        return new ConfirmImportResponse(created, duplicate, failed, rows);
    }

    private List<String> canonicalSelection(List<String> selectedDedupKeys) {
        return selectedDedupKeys == null ? List.of() : selectedDedupKeys.stream().sorted().toList();
    }

    private void sleepPollInterval() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a concurrent statement confirmation row", e);
        }
    }

    // ── upload: fingerprint construction ────────────────────────────────────────

    /**
     * Builds the row map list staged into {@code VaultDocument.payload}, assigning each row a
     * stable fingerprint. Prefers OFX {@code FITID} (already unique per source, so no ordinal is
     * needed); otherwise falls back to a fingerprint of the normalized fields plus an occurrence
     * ordinal — "the Nth time this exact base fingerprint has been seen so far, in file order" —
     * so two genuinely identical rows in one file get distinct fingerprints (and both survive)
     * while re-uploading the byte-identical file reproduces the same ordinals, and therefore the
     * same fingerprints, in the same order.
     */
    private List<Map<String, Object>> buildRowMaps(Long userId, Long accountId, List<ParsedStatementRow> rows) {
        Map<String, Integer> occurrenceCounts = new HashMap<>();
        List<Map<String, Object>> rowMaps = new ArrayList<>(rows.size());
        for (ParsedStatementRow row : rows) {
            String fingerprint = buildFingerprint(userId, accountId, row, occurrenceCounts);
            rowMaps.add(Map.of(
                    "date", row.date().toString(),
                    "amount", row.amount(),
                    "type", row.type().name(),
                    "description", row.description(),
                    "dedupKey", fingerprint
            ));
        }
        return rowMaps;
    }

    private String buildFingerprint(Long userId, Long accountId, ParsedStatementRow row,
                                     Map<String, Integer> occurrenceCounts) {
        if (row.fitId() != null && !row.fitId().isBlank()) {
            String raw = userId + "|" + accountId + "|fitid|" + row.fitId();
            return sha256Hex(raw);
        }
        String base = userId + "|" + accountId + "|" + row.type().name() + "|"
                + row.amount().toPlainString() + "|"
                + row.date() + "|"
                + row.description().strip().toLowerCase();
        int ordinal = occurrenceCounts.merge(base, 1, Integer::sum) - 1;
        return sha256Hex(base + "|" + ordinal);
    }

    private String sha256Hex(String raw) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
