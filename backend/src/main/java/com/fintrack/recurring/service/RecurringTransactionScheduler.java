package com.fintrack.recurring.service;

import com.fintrack.recurring.domain.RecurringTransaction;
import com.fintrack.recurring.repository.RecurringTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Cron trigger that fetches due recurring definitions and delegates each to
 * RecurringOccurrenceProcessor, which owns the @Transactional boundary per occurrence.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecurringTransactionScheduler {

    private final RecurringTransactionRepository recurringRepository;
    private final RecurringOccurrenceProcessor processor;

    @Scheduled(cron = "0 0 1 * * *")   // 01:00 UTC daily
    public void generateDueTransactions() {
        LocalDate today = LocalDate.now();
        List<RecurringTransaction> due = recurringRepository.findDueDefinitions(today);
        log.info("Recurring scheduler: {} definitions due", due.size());
        due.forEach(rt -> processor.process(rt, today));
    }
}
