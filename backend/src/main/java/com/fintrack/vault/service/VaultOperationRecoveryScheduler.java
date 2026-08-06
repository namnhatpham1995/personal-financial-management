package com.fintrack.vault.service;

import com.fintrack.vault.domain.VaultOperation;
import com.fintrack.vault.domain.VaultOperationState;
import com.fintrack.vault.repository.VaultOperationRepository;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Recovers durable vault operations stuck in {@code PROCESSING} because the process handling
 * them died before completing the upload or reassignment workflow.
 *
 * <p>The sweep is shared by every operation kind and delegates compensation to a strategy keyed
 * by {@link VaultOperation#getOperation()}. The bounded query and per-row exception boundary keep
 * a large backlog or one broken operation from monopolizing or aborting a sweep.
 */
@Slf4j
@Component
public class VaultOperationRecoveryScheduler {

    /** Ten minutes comfortably exceeds any real upload or reassignment phase. */
    static final Duration STALE_THRESHOLD = Duration.ofMinutes(10);

    /** Caps total stale operations processed by one sweep across all operation kinds. */
    static final int MAX_PER_RUN = 200;

    private final VaultOperationRepository operationRepository;
    private final Map<String, VaultOperationRecoveryStrategy> recoveryStrategies;
    private final VaultReassignmentOperationMigration legacyMigration;

    public VaultOperationRecoveryScheduler(
            VaultOperationRepository operationRepository,
            @Qualifier("vaultOperationRecoveryStrategies")
            Map<String, VaultOperationRecoveryStrategy> recoveryStrategies,
            VaultReassignmentOperationMigration legacyMigration) {
        this.operationRepository = operationRepository;
        this.recoveryStrategies = recoveryStrategies;
        this.legacyMigration = legacyMigration;
    }

    // Externalized so Testcontainers tests that invoke this method directly can set
    // vault.recovery.cron=- (Spring's disabled-trigger sentinel) and stop the real cron from
    // firing mid-test and racing the explicit call for the migration guard / ShedLock.
    @Scheduled(cron = "${vault.recovery.cron:0 */5 * * * *}")
    @SchedulerLock(name = "vaultOperationRecovery", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void recoverStaleOperations() {
        // The legacy reassignment rows must be visible before this first sweep queries the new
        // collection, even when the process starts between scheduled ticks.
        legacyMigration.migrateOnce();

        Instant cutoff = Instant.now().minus(STALE_THRESHOLD);
        Pageable bound = PageRequest.of(0, MAX_PER_RUN);
        List<VaultOperation> stale = operationRepository.findByStateAndCreatedAtBefore(
                VaultOperationState.PROCESSING, cutoff, bound);

        if (stale.isEmpty()) {
            return;
        }

        log.info("Vault operation recovery: found {} stale PROCESSING operation(s) older than {}",
                stale.size(), cutoff);

        for (VaultOperation operation : stale) {
            recoverOne(operation);
        }
    }

    private void recoverOne(VaultOperation operation) {
        try {
            VaultOperationRecoveryStrategy strategy = recoveryStrategies.get(operation.getOperation());
            if (strategy == null) {
                log.warn("Vault operation recovery: no strategy registered for operation={} id={}",
                        operation.getOperation(), operation.getId());
                return;
            }
            strategy.recover(operation);
        } catch (RuntimeException e) {
            log.error("Vault operation recovery: failed to recover stale operation {}",
                    operation.getId(), e);
        }
    }
}
