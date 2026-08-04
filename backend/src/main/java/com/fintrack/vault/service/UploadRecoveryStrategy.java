package com.fintrack.vault.service;

import com.fintrack.vault.domain.VaultOperation;
import com.fintrack.vault.domain.VaultOperationState;
import com.fintrack.vault.repository.VaultOperationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Compensates stale upload operations by deleting any orphaned GridFS binary. */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadRecoveryStrategy implements VaultOperationRecoveryStrategy {

    private final VaultOperationRepository operationRepository;
    private final GridFsFileStore gridFsFileStore;
    private final VaultOperationMetrics metrics;

    @Override
    public void recover(VaultOperation operation) {
        if (operation.getGridFsFileId() != null) {
            gridFsFileStore.delete(operation.getGridFsFileId());
            log.info("Vault operation recovery: deleted orphaned GridFS binary {} for stale operation {}",
                    operation.getGridFsFileId(), operation.getId());
            metrics.recoveryCompensated(operation.getOperation());
        }
        operation.setState(VaultOperationState.FAILED);
        operationRepository.save(operation);
        metrics.recoveryRecovered(operation.getOperation());
    }
}
