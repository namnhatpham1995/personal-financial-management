package com.fintrack.vault.domain;

/**
 * Durable outcome of one selected statement row within a
 * {@code STAGED -> CONFIRMING -> ACTIVE} statement confirmation. See {@code RowOutcome} and
 * {@code StatementImportService}.
 */
public enum RowOutcomeStatus {
    /** A per-row claim has been won and the row is currently being normalized into a transaction. */
    PROCESSING,
    /** A PostgreSQL transaction was created for this row. */
    CREATED,
    /** The row was already imported before (targeted {@code uq_transactions_user_import_dedup_key} conflict). */
    DUPLICATE,
    /** The row failed validation or hit a non-dedup integrity violation; see the recorded error. */
    FAILED
}
