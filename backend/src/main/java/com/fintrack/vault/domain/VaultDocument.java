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
}
