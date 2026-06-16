package com.fintrack.recurring.repository;

import com.fintrack.recurring.domain.RecurringTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RecurringTransactionRepository extends JpaRepository<RecurringTransaction, Long> {

    List<RecurringTransaction> findAllByUserId(Long userId);

    Optional<RecurringTransaction> findByIdAndUserId(Long id, Long userId);

    /** Fetch all active definitions due for generation on or before today. */
    @Query("SELECT r FROM RecurringTransaction r WHERE r.active = true AND r.nextRunDate <= :today")
    List<RecurringTransaction> findDueDefinitions(LocalDate today);

    /** Nullify recurring_id on generated transactions so they're retained when definition is deleted. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Transaction t SET t.recurringId = NULL WHERE t.recurringId = :recurringId")
    void unlinkGeneratedTransactions(Long recurringId);
}
