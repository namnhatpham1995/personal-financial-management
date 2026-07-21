package com.fintrack.vault.service;

import com.fintrack.vault.domain.VaultOperation;
import com.fintrack.vault.domain.VaultOperationState;
import com.fintrack.vault.repository.VaultOperationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VaultOperationRecoverySchedulerTest {

    @Mock VaultOperationRepository operationRepository;
    @Mock GridFsFileStore gridFsFileStore;
    @Mock VaultOperationMetrics metrics;
    @InjectMocks VaultOperationRecoveryScheduler scheduler;

    @Test
    void staleProcessingOperationWithBinary_deletesBinaryAndMarksFailed() {
        VaultOperation stale = VaultOperation.builder()
                .id("op-stale-1")
                .state(VaultOperationState.PROCESSING)
                .gridFsFileId("gridfs-orphan")
                .createdAt(Instant.now().minus(VaultOperationRecoveryScheduler.STALE_THRESHOLD).minusSeconds(60))
                .build();
        when(operationRepository.findByStateAndCreatedAtBefore(eq(VaultOperationState.PROCESSING), any(), any()))
                .thenReturn(List.of(stale));

        scheduler.recoverStaleOperations();

        verify(gridFsFileStore).delete("gridfs-orphan");
        ArgumentCaptor<VaultOperation> saved = ArgumentCaptor.forClass(VaultOperation.class);
        verify(operationRepository).save(saved.capture());
        assertThat(saved.getValue().getState()).isEqualTo(VaultOperationState.FAILED);
    }

    @Test
    void staleProcessingOperationWithoutBinary_marksFailedWithoutDelete() {
        VaultOperation stale = VaultOperation.builder()
                .id("op-stale-2")
                .state(VaultOperationState.PROCESSING)
                .gridFsFileId(null)
                .createdAt(Instant.now().minus(VaultOperationRecoveryScheduler.STALE_THRESHOLD).minusSeconds(60))
                .build();
        when(operationRepository.findByStateAndCreatedAtBefore(eq(VaultOperationState.PROCESSING), any(), any()))
                .thenReturn(List.of(stale));

        scheduler.recoverStaleOperations();

        verify(gridFsFileStore, never()).delete(any());
        ArgumentCaptor<VaultOperation> saved = ArgumentCaptor.forClass(VaultOperation.class);
        verify(operationRepository).save(saved.capture());
        assertThat(saved.getValue().getState()).isEqualTo(VaultOperationState.FAILED);
    }

    @Test
    void noStaleOperations_doesNothing() {
        when(operationRepository.findByStateAndCreatedAtBefore(eq(VaultOperationState.PROCESSING), any(), any()))
                .thenReturn(List.of());

        scheduler.recoverStaleOperations();

        verify(operationRepository, never()).save(any());
        verify(gridFsFileStore, never()).delete(any());
    }
}
