package com.fintrack.vault.domain;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * MongoDB document representing a stored receipt or bank statement.
 * The {@code payload} field is intentionally unstructured — each source
 * (CSV, OFX, manual receipt) stores its own shape without schema migration.
 */
@Document(collection = "vault_documents")
@CompoundIndexes({
    @CompoundIndex(name = "idx_user_captured", def = "{'userId': 1, 'capturedAt': -1}"),
    @CompoundIndex(name = "idx_user_transaction", def = "{'userId': 1, 'transactionId': 1}")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VaultDocument {

    @Id
    private String id;

    private Long userId;

    private VaultDocumentType type;

    private VaultDocumentStatus status;

    /** Source identifier — e.g. "csv", "ofx", "manual". */
    private String source;

    private Instant capturedAt;

    /**
     * Free-form nested payload: line items, merchant info, parsed statement rows, etc.
     * Shape varies by source — no migration needed when new sources are added.
     */
    private Map<String, Object> payload;

    /** GridFS ObjectId (hex string) for the original binary (receipt image / statement file). */
    private String gridFsFileId;

    /** Links this document to a PostgreSQL transaction id, if applicable. */
    private Long transactionId;

    private String originalFilename;

    // ── Statement confirmation state (STAGED -> CONFIRMING -> ACTIVE) ──────────────────────
    // See StatementImportService for the compare-and-set transition and resume/replay contract.

    /** SHA-256 hash of the Idempotency-Key that claimed the current/most recent confirmation attempt. */
    private String confirmationKeyHash;

    /**
     * SHA-256 hash of the confirmation operation name plus the canonical (sorted) selected-row
     * dedup keys. Used to detect the same key being reused with a different row selection.
     */
    private String confirmationRequestHash;

    private Instant confirmationStartedAt;

    private Instant confirmationCompletedAt;

    /**
     * Durable per-row outcome, keyed by row dedup key, for the current/most recent confirmation
     * attempt. Populated incrementally as each selected row is processed so a resumed or replayed
     * confirmation never reprocesses an already-decided row.
     */
    private Map<String, RowOutcome> confirmationRowOutcomes;
}
