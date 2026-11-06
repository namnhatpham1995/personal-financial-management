package com.fintrack.recurring.service;

import com.fintrack.recurring.domain.RecurringTransaction;
import com.fintrack.recurring.repository.RecurringTransactionRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecurringTransactionSchedulerTest {

    @Test
    void failingDefinition_doesNotSkipRemainingDefinitions() {
        RecurringTransactionRepository repository = mock(RecurringTransactionRepository.class);
        RecurringOccurrenceProcessor processor = mock(RecurringOccurrenceProcessor.class);
        RecurringTransaction broken = mock(RecurringTransaction.class);
        RecurringTransaction healthy = mock(RecurringTransaction.class);
        when(repository.findDueDefinitions(any())).thenReturn(List.of(broken, healthy));
        doThrow(new IllegalStateException("boom")).when(processor).process(eq(broken), any(LocalDate.class));

        new RecurringTransactionScheduler(repository, processor).generateDueTransactions();

        verify(processor).process(eq(healthy), any(LocalDate.class));
    }
}
