package com.fintrack.account.service;

import com.fintrack.account.domain.Account;
import com.fintrack.account.mapper.AccountMapper;
import com.fintrack.account.repository.AccountRepository;
import com.fintrack.account.web.dto.AccountResponse;
import com.fintrack.account.web.dto.CreateAccountRequest;
import com.fintrack.account.web.dto.UpdateAccountRequest;
import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.common.exception.ConflictException;
import com.fintrack.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
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
        return accountMapper.toResponse(accountRepository.save(account));
    }

    @Transactional
    public void delete(Long userId, Long accountId) {
        Account account = findOwned(userId, accountId);
        if (accountRepository.hasTransactions(accountId)) {
            throw new ConflictException("Cannot delete account with existing transactions. Remove transactions first.");
        }
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
