package com.fintrack.vault.service;

import com.fintrack.audit.support.AuditReplaySignal;
import com.fintrack.idempotency.exception.IdempotencyConflictException;
import com.fintrack.idempotency.service.IdempotencyHasher;
import com.fintrack.idempotency.service.IdempotencyKeyValidator;
import com.fintrack.vault.domain.VaultOperation;
import com.fintrack.vault.domain.VaultOperationState;
import com.fintrack.vault.repository.VaultOperationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit coverage for {@link VaultUploadIdempotencyCoordinator}'s claim/replay/conflict/compensation
 * logic in isolation, using a mocked {@link VaultOperationRepository} and {@link GridFsFileStore}.
 * Sequential/concurrent Testcontainers coverage against a real Mongo instance lives in
 * {@code VaultUploadIdempotencyIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class VaultUploadIdempotencyCoordinatorTest {

    @Mock VaultOperationRepository operationRepository;
    @Mock GridFsFileStore gridFsFileStore;
    @Mock MongoTemplate mongoTemplate;

    VaultUploadIdempotencyCoordinator coordinator;

    private static final Long USER_ID = 1L;
    private static final String OPERATION = "vault.upload";
    private static final String KEY = "test-idempotency-key-0123456789";
    private static final Map<String, String> PARAMS = Map.of("type", "RECEIPT");
    private static final byte[] BYTES = "file-bytes".getBytes();

    @BeforeEach
    void setUp() {
        when(mongoTemplate.indexOps(VaultOperation.class)).thenReturn(mock(IndexOperations.class));
        coordinator = new VaultUploadIdempotencyCoordinator(
                new IdempotencyKeyValidator(), new IdempotencyHasher(), operationRepository, gridFsFileStore,
                mongoTemplate, new AuditReplaySignal(), new VaultOperationMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void freshClaim_storesBinaryThenSavesDocument_marksCompleted() throws Exception {
        when(operationRepository.insert(any(VaultOperation.class)))
                .thenAnswer(inv -> {
                    VaultOperation op = inv.getArgument(0);
                    op.setId("op-1");
                    return op;
                });
        when(operationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        VaultUploadOutcome<String> outcome = coordinator.execute(
                USER_ID, OPERATION, KEY, PARAMS, BYTES,
                operationId -> "gridfs-1",
                gridFsFileId -> new VaultUploadResult<>("doc-1", "response-for-" + gridFsFileId),
                docId -> "should-not-be-called");

        assertThat(outcome.replayed()).isFalse();
        assertThat(outcome.response()).isEqualTo("response-for-gridfs-1");

        ArgumentCaptor<VaultOperation> savedOps = ArgumentCaptor.forClass(VaultOperation.class);
        verify(operationRepository, times(2)).save(savedOps.capture());
        VaultOperation finalState = savedOps.getAllValues().get(1);
        assertThat(finalState.getState()).isEqualTo(VaultOperationState.COMPLETED);
        assertThat(finalState.getGridFsFileId()).isEqualTo("gridfs-1");
        assertThat(finalState.getVaultDocumentId()).isEqualTo("doc-1");
    }

    @Test
    void documentSaveFails_afterGridFsStoreSucceeds_compensatesAndMarksFailed() {
        when(operationRepository.insert(any(VaultOperation.class)))
                .thenAnswer(inv -> {
                    VaultOperation op = inv.getArgument(0);
                    op.setId("op-2");
                    return op;
                });
        when(operationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> coordinator.execute(
                USER_ID, OPERATION, KEY, PARAMS, BYTES,
                operationId -> "gridfs-2",
                gridFsFileId -> {
                    throw new RuntimeException("mongo document save failed");
                },
                docId -> "unused"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("mongo document save failed");

        // Compensation: the orphaned binary must be deleted.
        verify(gridFsFileStore).delete("gridfs-2");

        ArgumentCaptor<VaultOperation> savedOps = ArgumentCaptor.forClass(VaultOperation.class);
        verify(operationRepository, times(2)).save(savedOps.capture());
        VaultOperation finalState = savedOps.getAllValues().get(1);
        assertThat(finalState.getState()).isEqualTo(VaultOperationState.FAILED);
        assertThat(finalState.getGridFsFileId()).isEqualTo("gridfs-2");
    }

    @Test
    void binaryStoreFails_marksFailedWithoutTouchingGridFsDelete() {
        when(operationRepository.insert(any(VaultOperation.class)))
                .thenAnswer(inv -> {
                    VaultOperation op = inv.getArgument(0);
                    op.setId("op-3");
                    return op;
                });
        when(operationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> coordinator.execute(
                USER_ID, OPERATION, KEY, PARAMS, BYTES,
                operationId -> {
                    throw new java.io.IOException("disk full");
                },
                gridFsFileId -> new VaultUploadResult<>("doc", "resp"),
                docId -> "unused"))
                .isInstanceOf(java.io.IOException.class);

        verify(gridFsFileStore, never()).delete(any());
        ArgumentCaptor<VaultOperation> savedOps = ArgumentCaptor.forClass(VaultOperation.class);
        verify(operationRepository).save(savedOps.capture());
        assertThat(savedOps.getValue().getState()).isEqualTo(VaultOperationState.FAILED);
    }

    @Test
    void sameKeySameHash_completedOperation_replaysWithoutTouchingGridFs() throws Exception {
        when(operationRepository.insert(any(VaultOperation.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        String requestHash = new IdempotencyHasher().hashMultipartRequest(OPERATION, PARAMS, BYTES);
        VaultOperation completed = VaultOperation.builder()
                .id("op-4").userId(USER_ID).operation(OPERATION)
                .requestHash(requestHash)
                .state(VaultOperationState.COMPLETED)
                .vaultDocumentId("existing-doc")
                .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                .build();
        when(operationRepository.findByUserIdAndOperationAndKeyHash(eq(USER_ID), eq(OPERATION), any()))
                .thenReturn(Optional.of(completed));

        VaultUploadOutcome<String> outcome = coordinator.execute(
                USER_ID, OPERATION, KEY, PARAMS, BYTES,
                operationId -> {
                    throw new AssertionError("must not store a second binary on replay");
                },
                gridFsFileId -> {
                    throw new AssertionError("must not save a second document on replay");
                },
                docId -> "replayed-response-for-" + docId);

        assertThat(outcome.replayed()).isTrue();
        assertThat(outcome.response()).isEqualTo("replayed-response-for-existing-doc");
        verify(gridFsFileStore, never()).delete(any());
    }

    @Test
    void sameKeyDifferentFile_completedOperation_throwsConflict() {
        when(operationRepository.insert(any(VaultOperation.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        VaultOperation completed = VaultOperation.builder()
                .id("op-5").userId(USER_ID).operation(OPERATION)
                .requestHash("a-different-hash-entirely")
                .state(VaultOperationState.COMPLETED)
                .vaultDocumentId("existing-doc")
                .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600))
                .build();
        when(operationRepository.findByUserIdAndOperationAndKeyHash(eq(USER_ID), eq(OPERATION), any()))
                .thenReturn(Optional.of(completed));

        assertThatThrownBy(() -> coordinator.execute(
                USER_ID, OPERATION, KEY, PARAMS, BYTES,
                operationId -> "should-not-store",
                gridFsFileId -> new VaultUploadResult<>("x", "y"),
                docId -> "should-not-replay"))
                .isInstanceOf(IdempotencyConflictException.class);
    }
}
