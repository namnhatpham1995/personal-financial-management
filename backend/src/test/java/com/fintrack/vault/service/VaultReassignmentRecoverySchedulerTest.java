package com.fintrack.vault.service;

import com.fintrack.vault.domain.VaultReassignmentOperation;
import com.fintrack.vault.domain.VaultReassignmentState;
import com.fintrack.vault.repository.VaultReassignmentOperationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VaultReassignmentRecoverySchedulerTest {

    @Mock VaultReassignmentOperationRepository operationRepository;
    @Mock VaultReassignmentService reassignmentService;
    @InjectMocks VaultReassignmentRecoveryScheduler scheduler;

    @Test
    void recoversOnlyBoundedStaleProcessingOperations() {
        VaultReassignmentOperation operation = VaultReassignmentOperation.builder()
                .id("op-1")
                .state(VaultReassignmentState.PROCESSING)
                .createdAt(Instant.now().minus(VaultReassignmentRecoveryScheduler.STALE_THRESHOLD).minusSeconds(1))
                .build();
        when(operationRepository.findByStateAndCreatedAtBefore(
                eq(VaultReassignmentState.PROCESSING), any(), any())).thenReturn(List.of(operation));

        scheduler.recoverStaleOperations();

        verify(reassignmentService).recoverStale(operation);
    }
}
