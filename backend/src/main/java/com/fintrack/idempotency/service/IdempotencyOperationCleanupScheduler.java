package com.fintrack.idempotency.service;

import com.fintrack.idempotency.repository.IdempotencyOperationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Bounded cleanup of {@code idempotency_operations} rows past their seven-day retention window
 * (design.md Migration Plan; tasks.md 9.4). Mirrors
 * {@link com.fintrack.vault.service.VaultOperationRecoveryScheduler}'s pattern for the
 * PostgreSQL side of this system.
 *
 * <p>Only {@code COMPLETED} rows past {@code expires_at} are ever deleted — see
 * {@link IdempotencyOperationRepository#deleteExpiredCompleted} for why {@code PROCESSING} rows
 * are never touched by this job regardless of age. This is a passive, low-priority sweep (unlike
 * {@code VaultOperationRecoveryScheduler}, which actively recovers stuck operations); it exists
 * only to bound the table's long-term size now that replayed idempotency responses are retained
 * for a fixed window rather than forever.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyOperationCleanupScheduler {

    /** Caps how many expired rows a single sweep run deletes, per the "bounded" task requirement. */
    static final int MAX_PER_RUN = 1000;

    private static final String CLEANUP_COUNTER = "idempotency.cleanup.deleted";

    private final IdempotencyOperationRepository repository;
    private final MeterRegistry meterRegistry;

    @Scheduled(cron = "0 0 * * * *")   // every hour
    @SchedulerLock(name = "idempotencyOperationCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void deleteExpiredCompletedOperations() {
        int deleted = repository.deleteExpiredCompleted(Instant.now(), MAX_PER_RUN);
        if (deleted == 0) {
            return;
        }

        log.info("Idempotency operation cleanup: deleted {} expired COMPLETED row(s)", deleted);
        Counter.builder(CLEANUP_COUNTER)
                .description("Expired COMPLETED idempotency_operations rows deleted per cleanup run")
                .register(meterRegistry)
                .increment(deleted);
    }
}
