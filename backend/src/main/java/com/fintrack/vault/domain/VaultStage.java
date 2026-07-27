package com.fintrack.vault.domain;

/**
 * User-facing lifecycle stage, derived from a statement's {@link VaultDocumentStatus} or a
 * receipt's latest ingestion run status. Stage is a presentation and filtering concept only —
 * it is never stored, and deriving it never mutates the underlying status.
 *
 * See {@link VaultStageMapper} for the exhaustive status-to-stage mapping.
 */
public enum VaultStage {
    /** Statement uploaded but not yet parsed for review. */
    READY_TO_IMPORT,
    /** Statement staged/confirming, or receipt awaiting ingestion review. */
    NEEDS_REVIEW,
    /** Receipt ingestion run currently extracting. */
    PROCESSING,
    /** Statement confirmed, or receipt ingestion committed. */
    IMPORTED,
    /** Receipt with no ingestion run yet. */
    NOT_PROCESSED,
    /** Receipt ingestion run failed. */
    FAILED,
    /** Receipt ingestion run rejected or invalidated. */
    DISMISSED
}
