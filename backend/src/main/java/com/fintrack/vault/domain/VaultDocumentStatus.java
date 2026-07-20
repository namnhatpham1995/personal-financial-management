package com.fintrack.vault.domain;

public enum VaultDocumentStatus {
    /** Statement upload awaiting user review before normalization. */
    STAGED,
    /**
     * Statement confirmation has been claimed for a specific Idempotency-Key/selected-row set and
     * is normalizing selected rows into PostgreSQL transactions. See
     * {@code StatementImportService} for the {@code STAGED -> CONFIRMING -> ACTIVE} compare-and-set
     * transition and durable per-row outcome tracking.
     */
    CONFIRMING,
    /** Fully committed document (receipt attached or statement confirmation completed). */
    ACTIVE
}
