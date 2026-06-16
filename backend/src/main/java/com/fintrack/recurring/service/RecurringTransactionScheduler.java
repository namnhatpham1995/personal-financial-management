package com.fintrack.recurring.service;

import com.fintrack.account.service.AccountService;
import com.fintrack.recurring.domain.RecurringTransaction;
import com.fintrack.recurring.repository.RecurringTransactionRepository;
import com.fintrack.transaction.domain.Transaction;
import com.fintrack.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Generates concrete transactions for due recurring definitions.
 * Idempotency: the unique constraint (recurring_id, occurrence_date) prevents double-inserts on retry.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecurringTransactionScheduler {

    private final RecurringTransactionRepository recurringRepository;
    private final TransactionRepository transactionRepository;
    private final AccountService accountService;

    @Scheduled(cron = "0 0 1 * * *")   // 01:00 UTC daily
    public void generateDueTransactions() {
        LocalDate today = LocalDate.now();
        List<RecurringTransaction> due = recurringRepository.findDueDefinitions(today);
        log.info("Recurring scheduler: {} definitions due", due.size());
        due.forEach(rt -> processDefinition(rt, today));
    }

    @Transactional
    protected void processDefinition(RecurringTransaction rt, LocalDate today) {
        LocalDate occurrenceDate = rt.getNextRunDate();

        try {
            Transaction tx = Transaction.builder()
                    .user(rt.getUser())
                    .account(rt.getAccount())
                    .category(rt.getCategory())
                    .transactionType(rt.getTransactionType())
                    .amount(rt.getAmount())
                    .transactionDate(occurrenceDate)
                    .note(rt.getNote())
                    .recurringId(rt.getId())
                    .occurrenceDate(occurrenceDate)
                    .build();

            transactionRepository.save(tx);
            applyBalanceDelta(rt, tx);
            log.debug("Generated transaction for recurring {} on {}", rt.getId(), occurrenceDate);
        } catch (DataIntegrityViolationException e) {
            // Unique constraint violation: occurrence already generated — safe to skip
            log.debug("Skipping already-generated occurrence for recurring {} on {}", rt.getId(), occurrenceDate);
        }

        // Advance next_run_date and update occurrence counter
        rt.setOccurrencesCount(rt.getOccurrencesCount() + 1);
        rt.setNextRunDate(RecurringTransactionService.computeNextRunDate(occurrenceDate, rt.getFrequency(), rt.getIntervalValue()));

        // Check end conditions
        if (shouldDeactivate(rt, today)) {
            rt.setActive(false);
            log.info("Deactivating recurring definition {} — end condition reached", rt.getId());
        }

        recurringRepository.save(rt);
    }

    private boolean shouldDeactivate(RecurringTransaction rt, LocalDate today) {
        if (rt.getEndDate() != null && !rt.getNextRunDate().isBefore(rt.getEndDate())) return true;
        if (rt.getMaxOccurrences() != null && rt.getOccurrencesCount() >= rt.getMaxOccurrences()) return true;
        return false;
    }

    private void applyBalanceDelta(RecurringTransaction rt, Transaction tx) {
        switch (rt.getTransactionType()) {
            case INCOME   -> accountService.adjustBalance(rt.getAccount().getId(), rt.getAmount());
            case EXPENSE  -> accountService.adjustBalance(rt.getAccount().getId(), rt.getAmount().negate());
            case TRANSFER -> {
                accountService.adjustBalance(rt.getAccount().getId(), rt.getAmount().negate());
                if (tx.getTransferAccount() != null) {
                    accountService.adjustBalance(tx.getTransferAccount().getId(), rt.getAmount());
                }
            }
        }
    }
}
