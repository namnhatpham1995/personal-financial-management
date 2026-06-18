package com.fintrack.recurring.service;

import com.fintrack.account.service.AccountService;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.category.service.CategoryService;
import com.fintrack.recurring.domain.Frequency;
import com.fintrack.recurring.domain.RecurringTransaction;
import com.fintrack.recurring.mapper.RecurringTransactionMapper;
import com.fintrack.recurring.repository.RecurringTransactionRepository;
import com.fintrack.recurring.web.dto.RecurringResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecurringTransactionServiceTest {

    @Mock RecurringTransactionRepository recurringRepository;
    @Mock UserRepository userRepository;
    @Mock AccountService accountService;
    @Mock CategoryService categoryService;
    @Mock RecurringTransactionMapper mapper;

    @InjectMocks RecurringTransactionService recurringTransactionService;

    // ─── computeNextRunDate: pure static logic ────────────────────────────────

    @Test
    void computeNextRunDate_daily_advancesByDays() {
        LocalDate base = LocalDate.of(2024, 1, 10);
        assertThat(RecurringTransactionService.computeNextRunDate(base, Frequency.DAILY, 1))
                .isEqualTo(LocalDate.of(2024, 1, 11));
        assertThat(RecurringTransactionService.computeNextRunDate(base, Frequency.DAILY, 3))
                .isEqualTo(LocalDate.of(2024, 1, 13));
    }

    @Test
    void computeNextRunDate_weekly_advancesByWeeks() {
        LocalDate base = LocalDate.of(2024, 1, 1);
        assertThat(RecurringTransactionService.computeNextRunDate(base, Frequency.WEEKLY, 1))
                .isEqualTo(LocalDate.of(2024, 1, 8));
        assertThat(RecurringTransactionService.computeNextRunDate(base, Frequency.WEEKLY, 2))
                .isEqualTo(LocalDate.of(2024, 1, 15));
    }

    @Test
    void computeNextRunDate_monthly_advancesByMonths() {
        LocalDate base = LocalDate.of(2024, 1, 31);
        // Jan 31 + 1 month = Feb 29 (2024 is leap year)
        assertThat(RecurringTransactionService.computeNextRunDate(base, Frequency.MONTHLY, 1))
                .isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(RecurringTransactionService.computeNextRunDate(base, Frequency.MONTHLY, 3))
                .isEqualTo(LocalDate.of(2024, 4, 30));
    }

    @Test
    void computeNextRunDate_yearly_advancesByYears() {
        LocalDate base = LocalDate.of(2024, 3, 15);
        assertThat(RecurringTransactionService.computeNextRunDate(base, Frequency.YEARLY, 1))
                .isEqualTo(LocalDate.of(2025, 3, 15));
        assertThat(RecurringTransactionService.computeNextRunDate(base, Frequency.YEARLY, 2))
                .isEqualTo(LocalDate.of(2026, 3, 15));
    }

    // ─── pause / resume ───────────────────────────────────────────────────────

    @Test
    void pause_setsActiveToFalse() {
        RecurringTransaction rt = RecurringTransaction.builder()
                .id(1L).active(true).nextRunDate(LocalDate.now().plusDays(5)).build();
        when(recurringRepository.findByIdAndUserId(1L, 99L)).thenReturn(Optional.of(rt));
        when(recurringRepository.save(rt)).thenReturn(rt);
        when(mapper.toResponse(rt)).thenReturn(mock(RecurringResponse.class));

        recurringTransactionService.pause(99L, 1L);

        assertThat(rt.isActive()).isFalse();
        verify(recurringRepository).save(rt);
    }

    @Test
    void resume_setsActiveToTrueAndKeepsFutureNextRunDate() {
        LocalDate futureDate = LocalDate.now().plusDays(3);
        RecurringTransaction rt = RecurringTransaction.builder()
                .id(2L).active(false).nextRunDate(futureDate).build();
        when(recurringRepository.findByIdAndUserId(2L, 99L)).thenReturn(Optional.of(rt));
        when(recurringRepository.save(rt)).thenReturn(rt);
        when(mapper.toResponse(rt)).thenReturn(mock(RecurringResponse.class));

        recurringTransactionService.resume(99L, 2L);

        assertThat(rt.isActive()).isTrue();
        // nextRunDate is in the future — must not be re-anchored to today
        assertThat(rt.getNextRunDate()).isEqualTo(futureDate);
    }

    @Test
    void resume_reanchorsNextRunDateWhenInPast() {
        LocalDate pastDate = LocalDate.now().minusDays(5);
        RecurringTransaction rt = RecurringTransaction.builder()
                .id(3L).active(false).nextRunDate(pastDate).build();
        when(recurringRepository.findByIdAndUserId(3L, 99L)).thenReturn(Optional.of(rt));
        when(recurringRepository.save(rt)).thenReturn(rt);
        when(mapper.toResponse(rt)).thenReturn(mock(RecurringResponse.class));

        recurringTransactionService.resume(99L, 3L);

        assertThat(rt.isActive()).isTrue();
        assertThat(rt.getNextRunDate()).isEqualTo(LocalDate.now());
    }
}
