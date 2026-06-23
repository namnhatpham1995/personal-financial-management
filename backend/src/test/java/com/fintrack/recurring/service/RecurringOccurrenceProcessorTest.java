package com.fintrack.recurring.service;

import com.fintrack.account.domain.Account;
import com.fintrack.account.service.AccountService;
import com.fintrack.auth.domain.User;
import com.fintrack.common.cache.CacheVersionService;
import com.fintrack.common.domain.TransactionType;
import com.fintrack.recurring.domain.Frequency;
import com.fintrack.recurring.domain.RecurringTransaction;
import com.fintrack.recurring.repository.RecurringTransactionRepository;
import com.fintrack.transaction.domain.Transaction;
import com.fintrack.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecurringOccurrenceProcessorTest {

    @Mock RecurringTransactionRepository recurringRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock AccountService accountService;
    @Mock CacheVersionService cacheVersionService;

    @InjectMocks RecurringOccurrenceProcessor processor;

    private Account account;
    private RecurringTransaction rt;
    private final LocalDate TODAY = LocalDate.of(2024, 6, 1);

    @BeforeEach
    void setUp() {
        User user = User.builder().id(1L).build();
        account = Account.builder().id(10L).build();

        rt = RecurringTransaction.builder()
                .id(42L)
                .user(user)
                .account(account)
                .transactionType(TransactionType.EXPENSE)
                .amount(new BigDecimal("100.00"))
                .frequency(Frequency.MONTHLY)
                .intervalValue(1)
                .nextRunDate(TODAY)
                .occurrencesCount(0)
                .build();
    }

    @Test
    void process_generatesTransactionAndAppliesBalance() {
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        processor.process(rt, TODAY);

        verify(transactionRepository).save(any(Transaction.class));
        // EXPENSE: balance is negated
        verify(accountService).adjustBalance(10L, new BigDecimal("100.00").negate());
        verify(recurringRepository).save(rt);
    }

    @Test
    void process_advancesNextRunDateAndIncrementsCount() {
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        processor.process(rt, TODAY);

        // Monthly interval from June 1 → July 1
        assertThat(rt.getNextRunDate()).isEqualTo(LocalDate.of(2024, 7, 1));
        assertThat(rt.getOccurrencesCount()).isEqualTo(1);
    }

    @Test
    void process_duplicateOccurrence_skipsBalanceAdjustmentStillAdvancesNextRun() {
        when(transactionRepository.existsByRecurringIdAndOccurrenceDate(rt.getId(), TODAY)).thenReturn(true);

        processor.process(rt, TODAY);

        // No save or balance adjustment — duplicate detected by pre-existence check
        verify(transactionRepository, never()).save(any());
        verify(accountService, never()).adjustBalance(anyLong(), any());
        // next_run_date still advances, count still increments
        assertThat(rt.getNextRunDate()).isEqualTo(LocalDate.of(2024, 7, 1));
        assertThat(rt.getOccurrencesCount()).isEqualTo(1);
        verify(recurringRepository).save(rt);
    }

    @Test
    void process_maxOccurrencesReached_deactivates() {
        rt.setMaxOccurrences(1);
        rt.setOccurrencesCount(0);
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        processor.process(rt, TODAY);

        // After processing: count becomes 1, which equals maxOccurrences → deactivate
        assertThat(rt.isActive()).isFalse();
    }

    @Test
    void process_endDateReached_deactivates() {
        // nextRunDate is June 1; after processing, nextRunDate becomes July 1 which is >= endDate June 30
        rt.setEndDate(LocalDate.of(2024, 6, 30));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        processor.process(rt, TODAY);

        assertThat(rt.isActive()).isFalse();
    }

    @Test
    void process_notYetAtEndDate_remainsActive() {
        rt.setEndDate(LocalDate.of(2024, 12, 31));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        processor.process(rt, TODAY);

        assertThat(rt.isActive()).isTrue();
    }
}
