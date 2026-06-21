package com.fintrack.vault.domain;

public enum VaultDocumentStatus {
    /** Statement upload awaiting user review before normalization. */
    STAGED,
    /** Fully committed document (receipt attached or statement confirmed). */
    ACTIVE
}
