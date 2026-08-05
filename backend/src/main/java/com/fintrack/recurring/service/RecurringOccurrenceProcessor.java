package com.fintrack.recurring.service;

import com.fintrack.account.service.AccountService;
import com.fintrack.common.cache.CacheVersionService;
import com.fintrack.recurring.domain.RecurringTransaction;
import com.fintrack.recurring.repository.RecurringTransactionRepository;
import com.fintrack.transaction.domain.Transaction;
import com.fintrack.transaction.repository.TransactionRepository;
import com.fintrack.transaction.service.BalanceImpactPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final CacheVersionService cacheVersionService;

    @Transactional
    public void process(RecurringTransaction rt, LocalDate today) {
        LocalDate occurrenceDate = rt.getNextRunDate();

        if (transactionRepository.existsByRecurringIdAndOccurrenceDate(rt.getId(), occurrenceDate)) {
            // Unique constraint (recurring_id, occurrence_date): already generated — skip without double-applying balance
            log.debug("Skipping already-generated occurrence for recurring {} on {}", rt.getId(), occurrenceDate);
        } else {
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
            cacheVersionService.bump(rt.getUser().getId());
            log.debug("Generated transaction for recurring {} on {}", rt.getId(), occurrenceDate);
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
        Long transferAccountId = tx.getTransferAccount() != null ? tx.getTransferAccount().getId() : null;
        // destinationAmount is null — recurring transactions have no destination-side amount
        // concept, so the policy falls back to the shared amount for both sides of a transfer.
        for (BalanceImpactPolicy.AccountEffect effect : BalanceImpactPolicy.apply(rt.getTransactionType(),
                rt.getAccount().getId(), transferAccountId, rt.getAmount(), null)) {
            if (effect.accountId() != null) {
                accountService.adjustBalance(effect.accountId(), effect.delta());
            }
        }
    }
}
