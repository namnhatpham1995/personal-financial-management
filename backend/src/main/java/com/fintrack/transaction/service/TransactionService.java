package com.fintrack.transaction.service;

import com.fintrack.account.domain.Account;
import com.fintrack.account.service.AccountService;
import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.category.domain.Category;
import com.fintrack.category.service.CategoryService;
import com.fintrack.common.cache.CacheVersionService;
import com.fintrack.common.domain.TransactionType;
import com.fintrack.common.dto.PageResponse;
import com.fintrack.common.exception.ResourceNotFoundException;
import com.fintrack.idempotency.service.BatchRowOutcome;
import com.fintrack.idempotency.service.IdempotentBatchRowExecutor;
import com.fintrack.transaction.domain.Transaction;
import com.fintrack.transaction.mapper.TransactionMapper;
import com.fintrack.transaction.repository.TransactionRepository;
import com.fintrack.transaction.repository.TransactionSpecification;
import com.fintrack.transaction.web.dto.CreateTransactionRequest;
import com.fintrack.transaction.web.dto.BatchTransactionResponse;
import com.fintrack.transaction.web.dto.BatchTransactionRowRequest;
import com.fintrack.transaction.web.dto.BatchTransactionRowResult;
import com.fintrack.transaction.web.dto.TransactionResponse;
import com.fintrack.transaction.web.dto.UpdateTransactionRequest;
import com.fintrack.transaction.web.dto.MutationWarning;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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
    private final CacheVersionService cacheVersionService;
    private final Validator validator;
    private final IdempotentBatchRowExecutor idempotentBatchRowExecutor;

    /**
     * Isolated create used by internal callers (receipt-ingestion agent commit, statement-import
     * confirmation) that rely on each row committing/failing independently of any surrounding
     * transaction. Do not change this method's signature, annotation, or behavior — see
     * {@link #createJoiningCallerTransaction(Long, CreateTransactionRequest)} for the variant used
     * by the idempotent HTTP create path.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransactionResponse create(Long userId, CreateTransactionRequest req) {
        return createInternal(userId, req, null);
    }

    /**
     * Same creation logic as {@link #create(Long, CreateTransactionRequest)} but joins the
     * caller's ambient transaction (default {@code REQUIRED} propagation) instead of suspending
     * it. Intended for the idempotent HTTP create path, where {@code IdempotencyClaimRunner}
     * already holds an open transaction and the claim row must commit atomically with this
     * mutation.
     */
    @Transactional
    public TransactionResponse createJoiningCallerTransaction(Long userId, CreateTransactionRequest req) {
        return createInternal(userId, req, null);
    }

    /**
     * Isolated create used by internal ingestion callers (receipt-ingestion agent commit,
     * statement-import confirmation) that need to attach an internal import-dedup fingerprint —
     * something no public request can ever supply, since {@link CreateTransactionRequest} has no
     * such field. Same isolation ({@code REQUIRES_NEW}) as {@link #create(Long, CreateTransactionRequest)}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransactionResponse createWithImportDedupKey(Long userId, CreateTransactionRequest req, String importDedupKey) {
        return createInternal(userId, req, importDedupKey);
    }

    private TransactionResponse createInternal(Long userId, CreateTransactionRequest req, String importDedupKey) {
        User user = userRepository.getReferenceById(userId);
        Account account = accountService.findOwned(userId, req.accountId());
        List<MutationWarning> warnings = new ArrayList<>();
        if (importDedupKey == null && transactionRepository
                .existsByUserIdAndAccountIdAndTransactionDateAndAmountAndTransactionTypeAndNote(
                        userId, req.accountId(), req.transactionDate(), req.amount(), req.transactionType(), req.note())) {
            warnings.add(new MutationWarning("possible_duplicate_transaction",
                    "A similar transaction already exists", req.accountId()));
        }

        Transaction tx = Transaction.builder()
                .user(user)
                .account(account)
                .transactionType(req.transactionType())
                .amount(req.amount())
                .transactionDate(req.transactionDate())
                .note(req.note())
                .importDedupKey(importDedupKey)
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
            boolean crossCurrency = !account.getCurrency().equals(dest.getCurrency());
            if (crossCurrency && req.destinationAmount() == null) {
                throw new IllegalArgumentException(
                        "destinationAmount is required for a TRANSFER between accounts with different currencies ("
                                + account.getCurrency() + " -> " + dest.getCurrency() + ")");
            }
            if (!crossCurrency && req.destinationAmount() != null) {
                throw new IllegalArgumentException(
                        "destinationAmount must be omitted for a TRANSFER between accounts with the same currency");
            }
            tx.setDestinationAmount(req.destinationAmount());
        } else if (req.destinationAmount() != null) {
            throw new IllegalArgumentException("destinationAmount is only valid for TRANSFER transactions");
        }

        addNegativeBalanceWarning(warnings, account, req.transactionType(), req.amount());

        Transaction saved = transactionRepository.save(tx);
        applyBalanceDelta(saved, saved.getAmount(), saved.getDestinationAmount());
        cacheVersionService.bump(userId);
        return TransactionResponse.withWarnings(transactionMapper.toResponse(saved), warnings);
    }

    /**
     * Processes each row independently via {@link IdempotentBatchRowExecutor} — each row claims,
     * runs, and commits in its own {@code REQUIRES_NEW} transaction, so one invalid or conflicting
     * row never rolls back a sibling row. See {@code IdempotentBatchCoordinator} for the aggregate
     * batch-level resumability wrapper this method runs inside of.
     */
    public BatchTransactionResponse createBatch(Long userId, List<BatchTransactionRowRequest> rows) {
        List<BatchTransactionRowResult> results = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            BatchTransactionRowRequest row = rows.get(index);
            if (row == null) {
                results.add(failed(index, null, "Transaction row must not be null"));
                continue;
            }
            String clientRequestId = row.clientRequestId();
            var violations = validator.validate(row);
            if (!violations.isEmpty()) {
                results.add(failed(index, clientRequestId, validationMessage(violations)));
                continue;
            }

            CreateTransactionRequest txReq = row.transaction();
            BatchRowOutcome<TransactionResponse> outcome = idempotentBatchRowExecutor.execute(
                    userId, clientRequestId, txReq, TransactionResponse.class,
                    () -> ResponseEntity.status(HttpStatus.CREATED)
                            .body(createInternal(userId, txReq, null)));

            results.add(toRowResult(index, clientRequestId, outcome));
        }
        return new BatchTransactionResponse(results);
    }

    private BatchTransactionRowResult toRowResult(int index, String clientRequestId,
                                                    BatchRowOutcome<TransactionResponse> outcome) {
        return switch (outcome.kind()) {
            case CREATED -> new BatchTransactionRowResult(index, BatchTransactionRowResult.Status.CREATED,
                    clientRequestId, outcome.result(), null);
            case REPLAYED -> new BatchTransactionRowResult(index, BatchTransactionRowResult.Status.REPLAYED,
                    clientRequestId, outcome.result(), null);
            case CONFLICT -> new BatchTransactionRowResult(index, BatchTransactionRowResult.Status.CONFLICT,
                    clientRequestId, null, outcome.errorMessage());
            case FAILED -> failed(index, clientRequestId, outcome.errorMessage());
        };
    }

    private BatchTransactionRowResult failed(int index, String clientRequestId, String error) {
        return new BatchTransactionRowResult(index, BatchTransactionRowResult.Status.FAILED, clientRequestId, null, error);
    }

    private String validationMessage(java.util.Set<? extends ConstraintViolation<?>> violations) {
        return violations.iterator().next().getMessage();
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> list(
            Long userId, Long accountId, LocalDate startDate, LocalDate endDate,
            Long categoryId, TransactionType type, String note, Long transferAccountId,
            String currency, int page, int size, String sortBy, String sortDir) {

        size = Math.min(size <= 0 ? DEFAULT_PAGE_SIZE : size, MAX_PAGE_SIZE);
        Sort sort = Sort.by(Sort.Direction.fromOptionalString(sortDir).orElse(Sort.Direction.DESC), sortBy == null ? "transactionDate" : sortBy);
        Pageable pageable = PageRequest.of(Math.max(page, 0), size, sort);

        Specification<Transaction> spec = Specification
                .where(TransactionSpecification.byUserId(userId))
                .and(TransactionSpecification.byAccountId(accountId))
                .and(TransactionSpecification.byTransferAccountId(transferAccountId))
                .and(TransactionSpecification.byStartDate(startDate))
                .and(TransactionSpecification.byEndDate(endDate))
                .and(TransactionSpecification.byCategoryId(categoryId))
                .and(TransactionSpecification.byType(type))
                .and(TransactionSpecification.byNoteContaining(note))
                .and(TransactionSpecification.byCurrency(currency));

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
        BigDecimal oldDestinationAmount = tx.getDestinationAmount();
        boolean isCrossCurrencyTransfer = tx.getTransactionType() == TransactionType.TRANSFER
                && tx.getTransferAccount() != null
                && !tx.getAccount().getCurrency().equals(tx.getTransferAccount().getCurrency());

        if (isCrossCurrencyTransfer && (req.amount() == null) != (req.destinationAmount() == null)) {
            throw new IllegalArgumentException(
                    "amount and destinationAmount must be supplied together when updating a cross-currency transfer");
        }
        if (!isCrossCurrencyTransfer && req.destinationAmount() != null) {
            throw new IllegalArgumentException("destinationAmount must be omitted for this transaction");
        }

        BigDecimal finalAmount = req.amount() == null ? oldAmount : req.amount();
        BigDecimal finalDestinationAmount = isCrossCurrencyTransfer
                ? (req.destinationAmount() == null ? oldDestinationAmount : req.destinationAmount())
                : oldDestinationAmount;

        List<MutationWarning> warnings = new ArrayList<>();
        BigDecimal balanceDelta = finalAmount.subtract(oldAmount);
        if (tx.getTransactionType() == TransactionType.EXPENSE || tx.getTransactionType() == TransactionType.TRANSFER) {
            addNegativeBalanceWarning(warnings, tx.getAccount(), tx.getTransactionType(), balanceDelta);
        }

        boolean amountsChanged = finalAmount.compareTo(oldAmount) != 0
                || amountsDiffer(finalDestinationAmount, oldDestinationAmount);
        if (amountsChanged) {
            // Reverse old balance effect, apply new
            reverseBalanceDelta(tx, oldAmount, oldDestinationAmount);
            tx.setAmount(finalAmount);
            tx.setDestinationAmount(finalDestinationAmount);
            applyBalanceDelta(tx, finalAmount, finalDestinationAmount);
        }
        if (req.transactionDate() != null) tx.setTransactionDate(req.transactionDate());
        if (req.note() != null) tx.setNote(req.note());
        if (req.categoryId() != null) {
            tx.setCategory(categoryService.findVisibleOrThrow(userId, req.categoryId()));
        }

        TransactionResponse result = transactionMapper.toResponse(transactionRepository.save(tx));
        cacheVersionService.bump(userId);
        return TransactionResponse.withWarnings(result, warnings);
    }

    @Transactional
    public void delete(Long userId, Long txId) {
        Transaction tx = findOwned(userId, txId);
        reverseBalanceDelta(tx, tx.getAmount(), tx.getDestinationAmount());
        transactionRepository.delete(tx);
        cacheVersionService.bump(userId);
    }

    // ─── Internal balance helpers ──────────────────────────────────────────────

    /**
     * Apply the signed balance delta for a transaction (create or amount-increase).
     * For TRANSFER, {@code destinationAmount} is the destination-side amount (falls back to
     * {@code amount} for same-currency transfers where destinationAmount is null).
     */
    private void applyBalanceDelta(Transaction tx, BigDecimal amount, BigDecimal destinationAmount) {
        switch (tx.getTransactionType()) {
            case INCOME   -> accountService.adjustBalance(tx.getAccount().getId(), amount);
            case EXPENSE  -> accountService.adjustBalance(tx.getAccount().getId(), amount.negate());
            case TRANSFER -> {
                accountService.adjustBalance(tx.getAccount().getId(), amount.negate());
                accountService.adjustBalance(tx.getTransferAccount().getId(),
                        destinationAmount != null ? destinationAmount : amount);
            }
        }
    }

    /** Reverse the balance delta (delete or pre-update). */
    private void reverseBalanceDelta(Transaction tx, BigDecimal amount, BigDecimal destinationAmount) {
        switch (tx.getTransactionType()) {
            case INCOME   -> accountService.adjustBalance(tx.getAccount().getId(), amount.negate());
            case EXPENSE  -> accountService.adjustBalance(tx.getAccount().getId(), amount);
            case TRANSFER -> {
                accountService.adjustBalance(tx.getAccount().getId(), amount);
                accountService.adjustBalance(tx.getTransferAccount().getId(),
                        (destinationAmount != null ? destinationAmount : amount).negate());
            }
        }
    }

    private boolean amountsDiffer(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return a != b;
        return a.compareTo(b) != 0;
    }

    private void addNegativeBalanceWarning(List<MutationWarning> warnings, Account account,
                                           TransactionType type, BigDecimal amount) {
        if ((type == TransactionType.EXPENSE || type == TransactionType.TRANSFER)
                && amount.signum() > 0
                && account.getCurrentBalance().subtract(amount).signum() < 0) {
            warnings.add(new MutationWarning("account_balance_negative",
                    "This mutation leaves the account balance negative", account.getId()));
        }
    }

    public Transaction findOwned(Long userId, Long txId) {
        return transactionRepository.findByIdAndUserId(txId, userId)
                .orElseThrow(() -> ResourceNotFoundException.of("Transaction", txId));
    }
}
