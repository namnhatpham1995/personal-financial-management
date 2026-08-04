package com.fintrack.vault.service;

import com.fintrack.vault.domain.VaultOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Delegates stale reassignment compensation to the reassignment saga service. */
@Component
@RequiredArgsConstructor
public class ReassignmentRecoveryStrategy implements VaultOperationRecoveryStrategy {

    private final VaultReassignmentService reassignmentService;

    @Override
    public void recover(VaultOperation operation) {
        reassignmentService.recoverStale(operation);
    }
}
