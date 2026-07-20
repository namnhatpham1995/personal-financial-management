package com.fintrack.vault.domain;

/**
 * Lifecycle of a {@link VaultOperation}. See {@code VaultUploadIdempotencyCoordinator} for the
 * claim/complete/compensate transitions.
 */
public enum VaultOperationState {
    /** Claimed and in flight — either storing the GridFS binary or saving the vault document. */
    PROCESSING,
    /** Binary and document both persisted; {@code gridFsFileId}/{@code vaultDocumentId} are set. */
    COMPLETED,
    /**
     * The claim was lost to a failure (document save error, or stale-operation sweep). Any
     * GridFS binary recorded on this row has already been compensated (deleted). A retry with the
     * same key may atomically reclaim a row in this state and try again.
     */
    FAILED
}
