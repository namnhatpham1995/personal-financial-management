package com.fintrack.transaction.service;

import com.fintrack.account.domain.Account;
import com.fintrack.account.service.AccountService;
import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.category.domain.Category;
import com.fintrack.category.service.CategoryService;
import com.fintrack.common.domain.TransactionType;
import com.fintrack.common.dto.PageResponse;
import com.fintrack.common.exception.ResourceNotFoundException;
import com.fintrack.transaction.domain.Transaction;
import com.fintrack.transaction.mapper.TransactionMapper;
import com.fintrack.transaction.repository.TransactionRepository;
import com.fintrack.transaction.repository.TransactionSpecification;
import com.fintrack.transaction.web.dto.CreateTransactionRequest;
import com.fintrack.transaction.web.dto.TransactionResponse;
import com.fintrack.transaction.web.dto.UpdateTransactionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final AccountService accountService;
    private final CategoryService categoryService;
    private final TransactionMapper transactionMapper;

    @Transactional
    public TransactionResponse create(Long userId, CreateTransactionRequest req) {
        User user = userRepository.getReferenceById(userId);
        Account account = accountService.findOwned(userId, req.accountId());

        Transaction tx = Transaction.builder()
                .user(user)
                .account(account)
                .transactionType(req.transactionType())
                .amount(req.amount())
                .transactionDate(req.transactionDate())
                .note(req.note())
                .importDedupKey(req.importDedupKey())
                .build();

        if (req.categoryId() != null) {
            Category category = categoryService.findVisibleOrThrow(userId, req.categoryId());
            tx.setCategory(category);
        }

        if (req.transactionType() == TransactionType.TRANSFER) {
            if (req.transferAccountId() == null) {
                throw new IllegalArgumentException("transferAccountId is required for TRANSFER transactions");
            }
            Account dest = accountService.findOwned(userId, req.transferAccountId());
            tx.setTransferAccount(dest);
        }

        Transaction saved = transactionRepository.save(tx);
        applyBalanceDelta(saved, saved.getAmount());
        return transactionMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> list(
            Long userId, Long accountId, LocalDate startDate, LocalDate endDate,
            Long categoryId, TransactionType type, String note,
            int page, int size, String sortBy, String sortDir) {

        size = Math.min(size <= 0 ? DEFAULT_PAGE_SIZE : size, MAX_PAGE_SIZE);
        Sort sort = Sort.by(Sort.Direction.fromOptionalString(sortDir).orElse(Sort.Direction.DESC), sortBy == null ? "transactionDate" : sortBy);
        Pageable pageable = PageRequest.of(Math.max(page, 0), size, sort);

        Specification<Transaction> spec = Specification
                .where(TransactionSpecification.byUserId(userId))
                .and(TransactionSpecification.byAccountId(accountId))
                .and(TransactionSpecification.byStartDate(startDate))
                .and(TransactionSpecification.byEndDate(endDate))
                .and(TransactionSpecification.byCategoryId(categoryId))
                .and(TransactionSpecification.byType(type))
                .and(TransactionSpecification.byNoteContaining(note));

        return PageResponse.of(
                transactionRepository.findAll(spec, pageable),
                transactionMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public TransactionResponse getById(Long userId, Long txId) {
        return transactionMapper.toResponse(findOwned(userId, txId));
    }

    @Transactional
    public TransactionResponse update(Long userId, Long txId, UpdateTransactionRequest req) {
        Transaction tx = findOwned(userId, txId);
        BigDecimal oldAmount = tx.getAmount();

        if (req.amount() != null && req.amount().compareTo(oldAmount) != 0) {
            // Reverse old balance effect, apply new
            reverseBalanceDelta(tx, oldAmount);
            tx.setAmount(req.amount());
            applyBalanceDelta(tx, req.amount());
        }
        if (req.transactionDate() != null) tx.setTransactionDate(req.transactionDate());
        if (req.note() != null) tx.setNote(req.note());
        if (req.categoryId() != null) {
            tx.setCategory(categoryService.findVisibleOrThrow(userId, req.categoryId()));
        }

        return transactionMapper.toResponse(transactionRepository.save(tx));
    }

    @Transactional
    public void delete(Long userId, Long txId) {
        Transaction tx = findOwned(userId, txId);
        reverseBalanceDelta(tx, tx.getAmount());
        transactionRepository.delete(tx);
    }

    // ─── Internal balance helpers ──────────────────────────────────────────────

    /** Apply the signed balance delta for a transaction (create or amount-increase). */
    private void applyBalanceDelta(Transaction tx, BigDecimal amount) {
        switch (tx.getTransactionType()) {
            case INCOME   -> accountService.adjustBalance(tx.getAccount().getId(), amount);
            case EXPENSE  -> accountService.adjustBalance(tx.getAccount().getId(), amount.negate());
            case TRANSFER -> {
                accountService.adjustBalance(tx.getAccount().getId(), amount.negate());
                accountService.adjustBalance(tx.getTransferAccount().getId(), amount);
            }
        }
    }

    /** Reverse the balance delta (delete or pre-update). */
    private void reverseBalanceDelta(Transaction tx, BigDecimal amount) {
        switch (tx.getTransactionType()) {
            case INCOME   -> accountService.adjustBalance(tx.getAccount().getId(), amount.negate());
            case EXPENSE  -> accountService.adjustBalance(tx.getAccount().getId(), amount);
            case TRANSFER -> {
                accountService.adjustBalance(tx.getAccount().getId(), amount);
                accountService.adjustBalance(tx.getTransferAccount().getId(), amount.negate());
            }
        }
    }

    public Transaction findOwned(Long userId, Long txId) {
        return transactionRepository.findByIdAndUserId(txId, userId)
                .orElseThrow(() -> ResourceNotFoundException.of("Transaction", txId));
    }
}
