package com.fintrack.vault.domain;

/** Lifecycle of a {@link VaultOperation} across upload and reassignment operations. */
public enum VaultOperationState {
    /** Claimed and in flight — storing an upload, saving a vault document, or reassignment cleanup. */
    PROCESSING,
    /** The operation completed successfully; reassignment results live in the operation payload. */
    COMPLETED,
    /**
     * The claim was lost to a failure or stale-operation sweep. Any GridFS binary recorded on
     * this row has already been compensated (deleted). A retry with the same key may reclaim it.
     */
    FAILED
}
