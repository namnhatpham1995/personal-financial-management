package com.fintrack.recurring.service;

import com.fintrack.account.service.AccountService;
import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.category.service.CategoryService;
import com.fintrack.common.exception.ResourceNotFoundException;
import com.fintrack.recurring.domain.Frequency;
import com.fintrack.recurring.domain.RecurringTransaction;
import com.fintrack.recurring.mapper.RecurringTransactionMapper;
import com.fintrack.recurring.repository.RecurringTransactionRepository;
import com.fintrack.recurring.web.dto.CreateRecurringRequest;
import com.fintrack.recurring.web.dto.RecurringResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RecurringTransactionService {

    private final RecurringTransactionRepository recurringRepository;
    private final UserRepository userRepository;
    private final AccountService accountService;
    private final CategoryService categoryService;
    private final RecurringTransactionMapper mapper;

    @Transactional
    public RecurringResponse create(Long userId, CreateRecurringRequest req) {
        User user = userRepository.getReferenceById(userId);
        var account = accountService.findOwned(userId, req.accountId());

        RecurringTransaction rt = RecurringTransaction.builder()
                .user(user)
                .account(account)
                .transactionType(req.transactionType())
                .amount(req.amount())
                .note(req.note())
                .frequency(req.frequency())
                .intervalValue(req.intervalValue())
                .startDate(req.startDate())
                .endDate(req.endDate())
                .maxOccurrences(req.maxOccurrences())
                .nextRunDate(req.startDate())
                .build();

        if (req.categoryId() != null) {
            rt.setCategory(categoryService.findVisibleOrThrow(userId, req.categoryId()));
        }

        return mapper.toResponse(recurringRepository.save(rt));
    }

    @Transactional(readOnly = true)
    public List<RecurringResponse> listByUser(Long userId) {
        return recurringRepository.findAllByUserId(userId).stream().map(mapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public RecurringResponse getById(Long userId, Long id) {
        return mapper.toResponse(findOwned(userId, id));
    }

    @Transactional
    public RecurringResponse pause(Long userId, Long id) {
        RecurringTransaction rt = findOwned(userId, id);
        rt.setActive(false);
        return mapper.toResponse(recurringRepository.save(rt));
    }

    @Transactional
    public RecurringResponse resume(Long userId, Long id) {
        RecurringTransaction rt = findOwned(userId, id);
        rt.setActive(true);
        // Re-anchor next_run_date to today if it's in the past
        if (rt.getNextRunDate() != null && rt.getNextRunDate().isBefore(LocalDate.now())) {
            rt.setNextRunDate(LocalDate.now());
        }
        return mapper.toResponse(recurringRepository.save(rt));
    }

    @Transactional
    public void delete(Long userId, Long id) {
        RecurringTransaction rt = findOwned(userId, id);
        // Nullify recurring_id on generated transactions so they're retained but unlinked
        recurringRepository.unlinkGeneratedTransactions(rt.getId());
        recurringRepository.delete(rt);
    }

    /** Advances next_run_date by frequency × interval. Returns whether the definition should stay active. */
    public static LocalDate computeNextRunDate(LocalDate current, Frequency frequency, int interval) {
        return switch (frequency) {
            case DAILY   -> current.plusDays(interval);
            case WEEKLY  -> current.plusWeeks(interval);
            case MONTHLY -> current.plusMonths(interval);
            case YEARLY  -> current.plusYears(interval);
        };
    }

    public RecurringTransaction findOwned(Long userId, Long id) {
        return recurringRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ResourceNotFoundException.of("RecurringTransaction", id));
    }
}
