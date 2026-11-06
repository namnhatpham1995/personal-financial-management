package com.fintrack.recurring.service;

import com.fintrack.recurring.domain.RecurringTransaction;
import com.fintrack.recurring.repository.RecurringTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Cron trigger that fetches due recurring definitions and delegates each to
 * RecurringOccurrenceProcessor, which owns the @Transactional boundary per occurrence.
 * ShedLock keeps it to one replica; each definition is isolated so one failure
 * does not skip the rest of the day's run.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecurringTransactionScheduler {

    private final RecurringTransactionRepository recurringRepository;
    private final RecurringOccurrenceProcessor processor;

    @Scheduled(cron = "0 0 1 * * *")   // 01:00 UTC daily
    @SchedulerLock(name = "recurringTransactionGeneration", lockAtMostFor = "PT1H", lockAtLeastFor = "PT30M")
    public void generateDueTransactions() {
        LocalDate today = LocalDate.now();
        List<RecurringTransaction> due = recurringRepository.findDueDefinitions(today);
        log.info("Recurring scheduler: {} definitions due", due.size());
        int failed = 0;
        for (RecurringTransaction rt : due) {
            try {
                processor.process(rt, today);
            } catch (RuntimeException e) {
                failed++;
                log.error("Recurring scheduler: definition {} failed, continuing", rt.getId(), e);
            }
        }
        if (failed > 0) {
            log.warn("Recurring scheduler: {} of {} definitions failed", failed, due.size());
        }
    }
}
