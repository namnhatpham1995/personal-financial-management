package com.fintrack.vault.service;

import com.fintrack.vault.domain.VaultOperation;
import com.fintrack.vault.domain.VaultOperationState;
import com.fintrack.vault.repository.VaultOperationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Recovers vault/statement upload operations stuck in {@code PROCESSING} because the process
 * handling them died between claiming the operation and completing the document save (design.md
 * Decision #4, "Process stops during upload" scenario in the document-vault spec).
 *
 * <p>Distinct from {@link com.fintrack.vault.domain.VaultOperation}'s TTL index: the TTL index is
 * passive long-term cleanup of anything (COMPLETED, FAILED, or otherwise) past its seven-day
 * retention window. This job actively hunts for operations that have been PROCESSING for far
 * longer than any real upload should take, and compensates them immediately so a retry with the
 * same key can cleanly reclaim rather than waiting behind a permanently-stuck row.
 *
 * <p>{@link SchedulerLock} follows {@code ExchangeRateRefreshScheduler}'s pattern so only one
 * replica runs a sweep at a time.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VaultOperationRecoveryScheduler {

    /**
     * An upload that has been PROCESSING for longer than this is considered abandoned by its
     * claimant. 10 minutes comfortably exceeds any real upload/parse duration (including large
     * statement files) while still recovering stuck operations promptly after a process death.
     */
    static final Duration STALE_THRESHOLD = Duration.ofMinutes(10);

    /** Caps how many stale operations a single sweep run compensates, per the "bounded" task requirement. */
    static final int MAX_PER_RUN = 200;

    private final VaultOperationRepository operationRepository;
    private final GridFsFileStore gridFsFileStore;
    private final VaultOperationMetrics metrics;

    @Scheduled(cron = "0 */5 * * * *")   // every 5 minutes
    @SchedulerLock(name = "vaultOperationRecovery", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void recoverStaleOperations() {
        Instant cutoff = Instant.now().minus(STALE_THRESHOLD);
        Pageable bound = PageRequest.of(0, MAX_PER_RUN);
        List<VaultOperation> stale = operationRepository.findByStateAndCreatedAtBefore(
                VaultOperationState.PROCESSING, cutoff, bound);

        if (stale.isEmpty()) {
            return;
        }

        log.info("Vault operation recovery: found {} stale PROCESSING operation(s) older than {}",
                stale.size(), cutoff);

        for (VaultOperation op : stale) {
            recoverOne(op);
        }
    }

    private void recoverOne(VaultOperation op) {
        try {
            if (op.getGridFsFileId() != null) {
                gridFsFileStore.delete(op.getGridFsFileId());
                log.info("Vault operation recovery: deleted orphaned GridFS binary {} for stale operation {}",
                        op.getGridFsFileId(), op.getId());
                metrics.recoveryCompensated(op.getOperation());
            }
            op.setState(VaultOperationState.FAILED);
            operationRepository.save(op);
            metrics.recoveryRecovered(op.getOperation());
        } catch (RuntimeException e) {
            log.error("Vault operation recovery: failed to recover stale operation {}", op.getId(), e);
        }
    }
}
