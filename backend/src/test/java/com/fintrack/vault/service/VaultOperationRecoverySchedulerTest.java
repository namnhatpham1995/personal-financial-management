package com.fintrack.vault.service;

import com.fintrack.vault.domain.VaultOperation;
import com.fintrack.vault.domain.VaultOperationState;
import com.fintrack.vault.repository.VaultOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VaultOperationRecoverySchedulerTest {

    @Mock VaultOperationRepository operationRepository;
    @Mock GridFsFileStore gridFsFileStore;
    @Mock VaultOperationMetrics metrics;
    @Mock VaultOperationRecoveryStrategy reassignmentRecoveryStrategy;
    @Mock VaultReassignmentOperationMigration migration;

    private VaultOperationRecoveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        UploadRecoveryStrategy uploadRecoveryStrategy = new UploadRecoveryStrategy(
                operationRepository, gridFsFileStore, metrics);
        scheduler = new VaultOperationRecoveryScheduler(
                operationRepository,
                Map.of(
                        "vault.upload", uploadRecoveryStrategy,
                        "statement.upload", uploadRecoveryStrategy,
                        "vault.reassign", reassignmentRecoveryStrategy),
                migration);
    }

    @Test
    void staleProcessingUploadWithBinary_deletesBinaryAndMarksFailed() {
        VaultOperation stale = stale("op-stale-1", "vault.upload");
        stale.setGridFsFileId("gridfs-orphan");
        when(operationRepository.findByStateAndCreatedAtBefore(eq(VaultOperationState.PROCESSING), any(), any()))
                .thenReturn(List.of(stale));

        scheduler.recoverStaleOperations();

        verify(gridFsFileStore).delete("gridfs-orphan");
        ArgumentCaptor<VaultOperation> saved = ArgumentCaptor.forClass(VaultOperation.class);
        verify(operationRepository).save(saved.capture());
        assertThat(saved.getValue().getState()).isEqualTo(VaultOperationState.FAILED);
    }

    @Test
    void staleProcessingUploadWithoutBinary_marksFailedWithoutDelete() {
        VaultOperation stale = stale("op-stale-2", "statement.upload");
        when(operationRepository.findByStateAndCreatedAtBefore(eq(VaultOperationState.PROCESSING), any(), any()))
                .thenReturn(List.of(stale));

        scheduler.recoverStaleOperations();

        verify(gridFsFileStore, never()).delete(any());
        ArgumentCaptor<VaultOperation> saved = ArgumentCaptor.forClass(VaultOperation.class);
        verify(operationRepository).save(saved.capture());
        assertThat(saved.getValue().getState()).isEqualTo(VaultOperationState.FAILED);
    }

    @Test
    void staleReassignmentOperation_dispatchesToReassignmentStrategy() {
        VaultOperation stale = stale("op-reassign", "vault.reassign");
        when(operationRepository.findByStateAndCreatedAtBefore(eq(VaultOperationState.PROCESSING), any(), any()))
                .thenReturn(List.of(stale));

        scheduler.recoverStaleOperations();

        verify(reassignmentRecoveryStrategy).recover(stale);
        verify(operationRepository, never()).save(any());
    }

    @Test
    void unrecognizedOperation_isLoggedAndSkippedWithoutThrowing() {
        VaultOperation stale = stale("op-unknown", "vault.future");
        when(operationRepository.findByStateAndCreatedAtBefore(eq(VaultOperationState.PROCESSING), any(), any()))
                .thenReturn(List.of(stale));

        scheduler.recoverStaleOperations();

        verifyNoInteractions(reassignmentRecoveryStrategy, gridFsFileStore, metrics);
        verify(operationRepository, never()).save(any());
    }

    @Test
    void noStaleOperations_doesNothing() {
        when(operationRepository.findByStateAndCreatedAtBefore(eq(VaultOperationState.PROCESSING), any(), any()))
                .thenReturn(List.of());

        scheduler.recoverStaleOperations();

        verify(operationRepository, never()).save(any());
        verify(gridFsFileStore, never()).delete(any());
    }

    @Test
    void migrationRunsBeforeTheFirstRecoveryQuery() {
        when(operationRepository.findByStateAndCreatedAtBefore(eq(VaultOperationState.PROCESSING), any(), any()))
                .thenReturn(List.of());

        scheduler.recoverStaleOperations();

        InOrder order = inOrder(migration, operationRepository);
        order.verify(migration).migrateOnce();
        order.verify(operationRepository).findByStateAndCreatedAtBefore(
                eq(VaultOperationState.PROCESSING), any(), any());
    }

    private static VaultOperation stale(String id, String operation) {
        return VaultOperation.builder()
                .id(id)
                .operation(operation)
                .state(VaultOperationState.PROCESSING)
                .createdAt(Instant.now().minus(VaultOperationRecoveryScheduler.STALE_THRESHOLD).minusSeconds(60))
                .build();
    }
}
