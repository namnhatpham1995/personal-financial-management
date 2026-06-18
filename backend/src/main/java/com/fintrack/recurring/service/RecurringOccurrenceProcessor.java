package com.fintrack.recurring.service;

import com.fintrack.account.service.AccountService;
import com.fintrack.recurring.domain.RecurringTransaction;
import com.fintrack.recurring.repository.RecurringTransactionRepository;
import com.fintrack.transaction.domain.Transaction;
import com.fintrack.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Processes a single recurring definition occurrence as an isolated Spring bean so the
 * @Transactional boundary applies correctly (cross-bean delegation goes through the proxy).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecurringOccurrenceProcessor {

    private final RecurringTransactionRepository recurringRepository;
    private final TransactionRepository transactionRepository;
    private final AccountService accountService;

    @Transactional
    public void process(RecurringTransaction rt, LocalDate today) {
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
            // Unique constraint (recurring_id, occurrence_date): already generated — skip without double-applying balance
            log.debug("Skipping already-generated occurrence for recurring {} on {}", rt.getId(), occurrenceDate);
        }

        rt.setOccurrencesCount(rt.getOccurrencesCount() + 1);
        rt.setNextRunDate(RecurringTransactionService.computeNextRunDate(occurrenceDate, rt.getFrequency(), rt.getIntervalValue()));

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
