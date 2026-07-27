package com.fintrack.vault.service;

import com.fintrack.vault.domain.VaultReassignmentOperation;
import com.fintrack.vault.domain.VaultReassignmentState;
import com.fintrack.vault.repository.VaultReassignmentOperationRepository;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class VaultReassignmentRecoveryScheduler {

    static final Duration STALE_THRESHOLD = Duration.ofMinutes(10);
    static final int MAX_PER_RUN = 100;

    private final VaultReassignmentOperationRepository operationRepository;
    private final VaultReassignmentService reassignmentService;

    @Scheduled(cron = "0 */5 * * * *")
    @SchedulerLock(name = "vaultReassignmentRecovery", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void recoverStaleOperations() {
        Instant cutoff = Instant.now().minus(STALE_THRESHOLD);
        operationRepository.findByStateAndCreatedAtBefore(
                        VaultReassignmentState.PROCESSING, cutoff, PageRequest.of(0, MAX_PER_RUN))
                .forEach(reassignmentService::recoverStale);
    }
}
