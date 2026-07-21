package com.fintrack.idempotency.service;

import com.fintrack.idempotency.repository.IdempotencyOperationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link IdempotencyOperationCleanupScheduler}'s delegation to
 * {@link IdempotencyOperationRepository#deleteExpiredCompleted}. The repository query itself
 * (which state/expiry combinations are actually deleted) is proven against a real PostgreSQL
 * instance in {@code IdempotencyOperationRepositoryTest} — a mocked repository here cannot
 * meaningfully re-verify SQL semantics, only that the scheduler calls through correctly and
 * reacts to the returned count.
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyOperationCleanupSchedulerTest {

    @Mock IdempotencyOperationRepository repository;

    IdempotencyOperationCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new IdempotencyOperationCleanupScheduler(repository, new SimpleMeterRegistry());
    }

    @Test
    void expiredRowsFound_deletesBoundedByMaxPerRun() {
        when(repository.deleteExpiredCompleted(any(Instant.class), anyInt())).thenReturn(3);

        scheduler.deleteExpiredCompletedOperations();

        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(repository).deleteExpiredCompleted(any(Instant.class), limitCaptor.capture());
        assertThat(limitCaptor.getValue()).isEqualTo(IdempotencyOperationCleanupScheduler.MAX_PER_RUN);
    }

    @Test
    void noExpiredRows_doesNothingFurther() {
        when(repository.deleteExpiredCompleted(any(Instant.class), anyInt())).thenReturn(0);

        scheduler.deleteExpiredCompletedOperations();

        verify(repository).deleteExpiredCompleted(any(Instant.class), anyInt());
    }
}
