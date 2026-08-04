package com.fintrack.vault.service;

import com.fintrack.vault.domain.VaultOperation;

/** Compensates one stale operation from the unified vault operation collection. */
public interface VaultOperationRecoveryStrategy {

    /** Performs operation-specific compensation and persists the resulting state. */
    void recover(VaultOperation operation);
}
