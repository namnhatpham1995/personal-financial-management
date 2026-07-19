package com.fintrack.account.service;

import com.fintrack.account.domain.Account;
import com.fintrack.account.mapper.AccountMapper;
import com.fintrack.account.repository.AccountRepository;
import com.fintrack.account.web.dto.AccountDeletePreviewDto;
import com.fintrack.account.web.dto.AccountResponse;
import com.fintrack.account.web.dto.CreateAccountRequest;
import com.fintrack.account.web.dto.UpdateAccountRequest;
import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.common.cache.CacheVersionService;
import com.fintrack.common.domain.TransactionType;
import com.fintrack.common.exception.ResourceNotFoundException;
import com.fintrack.exchangerate.service.ExchangeRateService;
import com.fintrack.transaction.domain.Transaction;
import com.fintrack.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final AccountMapper accountMapper;
    private final ExchangeRateService exchangeRateService;
    private final CacheVersionService cacheVersionService;

    @Transactional
    public AccountResponse create(Long userId, CreateAccountRequest request) {
        validateCurrency(request.currency());
        User user = userRepository.getReferenceById(userId);
        Account account = accountMapper.toEntity(request);
        account.setUser(user);
        AccountResponse response = accountMapper.toResponse(accountRepository.save(account));
        cacheVersionService.bump(userId);
        return response;
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> listByUser(Long userId) {
        return accountMapper.toResponseList(accountRepository.findAllByUserId(userId));
    }

    @Transactional(readOnly = true)
    public AccountResponse getById(Long userId, Long accountId) {
        return accountMapper.toResponse(findOwned(userId, accountId));
    }

    @Transactional
    public AccountResponse update(Long userId, Long accountId, UpdateAccountRequest request) {
        if (request.currency() != null) {
            validateCurrency(request.currency());
        }
        // The initial-balance path reads (computeBalanceFromTransactions) then overwrites
        // current_balance with an absolute value, so it must hold PESSIMISTIC_WRITE on the
        // account row for the duration of that read-compute-write — otherwise a concurrently
        // committing transaction create/update/delete's balance effect can be silently clobbered
        // by this overwrite (see openspec/changes/harden-idempotent-mutations/design.md
        // Decision #3). Non-initial-balance updates don't touch current_balance, but they share
        // the same lookup for simplicity since the lock is scoped to this transaction only.
        Account account = request.initialBalance() != null
                ? findOwnedForUpdate(userId, accountId)
                : findOwned(userId, accountId);
        accountMapper.updateEntity(request, account);
        if (request.initialBalance() != null) {
            // Recompute current balance so invariant holds: current = initial + Σ transactions
            BigDecimal txNet = accountRepository.computeBalanceFromTransactions(accountId);
            account.setCurrentBalance(account.getInitialBalance().add(txNet));
        }
        AccountResponse response = accountMapper.toResponse(accountRepository.save(account));
        cacheVersionService.bump(userId);
        return response;
    }

    @Transactional(readOnly = true)
    public AccountDeletePreviewDto getDeletePreview(Long userId, Long accountId) {
        findOwned(userId, accountId);
        long count = accountRepository.countConnectedTransactions(accountId);
        return new AccountDeletePreviewDto(accountId, count);
    }

    @Transactional
    public void delete(Long userId, Long accountId) {
        Account account = findOwned(userId, accountId);
        List<Transaction> connected = transactionRepository.findConnectedToAccount(accountId);
        // Reverse balance on counterparty accounts of transfers so their balances stay correct
        for (Transaction tx : connected) {
            if (tx.getTransactionType() == TransactionType.TRANSFER) {
                if (tx.getAccount().getId().equals(accountId)) {
                    // This account is the transfer source — counterparty (dest) gained
                    // destinationAmount (or amount for same-currency transfers), reverse it
                    BigDecimal destEffect = tx.getDestinationAmount() != null ? tx.getDestinationAmount() : tx.getAmount();
                    adjustBalance(tx.getTransferAccount().getId(), destEffect.negate());
                } else {
                    // This account is the transfer dest — counterparty (source) lost amount, restore it
                    adjustBalance(tx.getAccount().getId(), tx.getAmount());
                }
            }
        }
        transactionRepository.deleteAll(connected);
        accountRepository.delete(account);
        cacheVersionService.bump(userId);
    }

    /**
     * Adjusts current_balance by delta — called by TransactionService on create/update/delete.
     * Must be called within an existing transaction.
     */
    @Transactional
    public void adjustBalance(Long accountId, BigDecimal delta) {
        if (!accountRepository.existsById(accountId)) {
            throw ResourceNotFoundException.of("Account", accountId);
        }
        accountRepository.atomicAdjustBalance(accountId, delta);
    }

    /**
     * Recomputes current_balance from initial_balance + sum of all transactions.
     * Used as a safety-net routine to correct any drift.
     */
    @Transactional
    public void recomputeBalance(Long userId, Long accountId) {
        // Same read-compute-write-under-lock reasoning as the initial-balance path in update().
        Account account = findOwnedForUpdate(userId, accountId);
        BigDecimal computed = accountRepository.computeBalanceFromTransactions(accountId);
        account.setCurrentBalance(account.getInitialBalance().add(computed));
        accountRepository.save(account);
        cacheVersionService.bump(userId);
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    public Account findOwned(Long userId, Long accountId) {
        return accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> ResourceNotFoundException.of("Account", accountId));
    }

    /**
     * Owned-account lookup with {@code PESSIMISTIC_WRITE} held for the duration of the caller's
     * transaction. Used by {@code TransactionService} to serialize update/delete balance effects
     * against this account, and internally by initial-balance change / {@link #recomputeBalance}
     * so their read-compute-write cannot be interleaved with a concurrent balance mutation.
     */
    public Account findOwnedForUpdate(Long userId, Long accountId) {
        return accountRepository.findByIdAndUserIdForUpdate(accountId, userId)
                .orElseThrow(() -> ResourceNotFoundException.of("Account", accountId));
    }

    /**
     * Validates the requested currency against the supported set from the exchange rate cache.
     * When the cache is empty (cold start), the seed fallback inside {@code supportedCurrencies()}
     * is used, so account creation is never blocked on a cold cache.
     * Throws {@link IllegalArgumentException} (mapped to HTTP 400 by {@code GlobalExceptionHandler})
     * if the currency is present in a non-empty cache but not supported.
     */
    private void validateCurrency(String currency) {
        Set<String> supported = exchangeRateService.supportedCurrencies();
        if (!supported.contains(currency)) {
            throw new IllegalArgumentException(
                    "Unsupported currency: " + currency + ". Must be one of the supported ISO 4217 codes.");
        }
    }
}
