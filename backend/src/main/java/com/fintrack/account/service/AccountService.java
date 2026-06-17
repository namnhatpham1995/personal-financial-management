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
import com.fintrack.common.domain.TransactionType;
import com.fintrack.common.exception.ResourceNotFoundException;
import com.fintrack.transaction.domain.Transaction;
import com.fintrack.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final AccountMapper accountMapper;

    @Transactional
    public AccountResponse create(Long userId, CreateAccountRequest request) {
        User user = userRepository.getReferenceById(userId);
        Account account = accountMapper.toEntity(request);
        account.setUser(user);
        return accountMapper.toResponse(accountRepository.save(account));
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
        Account account = findOwned(userId, accountId);
        accountMapper.updateEntity(request, account);
        if (request.initialBalance() != null) {
            // Recompute current balance so invariant holds: current = initial + Σ transactions
            BigDecimal txNet = accountRepository.computeBalanceFromTransactions(accountId);
            account.setCurrentBalance(account.getInitialBalance().add(txNet));
        }
        return accountMapper.toResponse(accountRepository.save(account));
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
                    // This account is the transfer source — counterparty (dest) gained amount, reverse it
                    adjustBalance(tx.getTransferAccount().getId(), tx.getAmount().negate());
                } else {
                    // This account is the transfer dest — counterparty (source) lost amount, restore it
                    adjustBalance(tx.getAccount().getId(), tx.getAmount());
                }
            }
        }
        transactionRepository.deleteAll(connected);
        accountRepository.delete(account);
    }

    /**
     * Adjusts current_balance by delta — called by TransactionService on create/update/delete.
     * Must be called within an existing transaction.
     */
    @Transactional
    public void adjustBalance(Long accountId, BigDecimal delta) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> ResourceNotFoundException.of("Account", accountId));
        account.setCurrentBalance(account.getCurrentBalance().add(delta));
        accountRepository.save(account);
    }

    /**
     * Recomputes current_balance from initial_balance + sum of all transactions.
     * Used as a safety-net routine to correct any drift.
     */
    @Transactional
    public void recomputeBalance(Long userId, Long accountId) {
        Account account = findOwned(userId, accountId);
        BigDecimal computed = accountRepository.computeBalanceFromTransactions(accountId);
        account.setCurrentBalance(account.getInitialBalance().add(computed));
        accountRepository.save(account);
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    public Account findOwned(Long userId, Long accountId) {
        return accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> ResourceNotFoundException.of("Account", accountId));
    }
}
