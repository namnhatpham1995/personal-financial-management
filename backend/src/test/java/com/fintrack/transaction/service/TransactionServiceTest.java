package com.fintrack.transaction.service;

import com.fintrack.account.domain.Account;
import com.fintrack.account.service.AccountService;
import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.category.service.CategoryService;
import com.fintrack.common.cache.CacheVersionService;
import com.fintrack.common.domain.TransactionType;
import com.fintrack.transaction.domain.Transaction;
import com.fintrack.transaction.mapper.TransactionMapper;
import com.fintrack.transaction.repository.TransactionRepository;
import com.fintrack.transaction.web.dto.CreateTransactionRequest;
import com.fintrack.transaction.web.dto.TransactionResponse;
import com.fintrack.transaction.web.dto.UpdateTransactionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock TransactionRepository transactionRepository;
    @Mock UserRepository userRepository;
    @Mock AccountService accountService;
    @Mock CategoryService categoryService;
    @Mock TransactionMapper transactionMapper;
    @Mock CacheVersionService cacheVersionService;

    @InjectMocks TransactionService transactionService;

    private Account account;
    private Account destAccount;
    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(1L).email("user@test.com").build();
        account = Account.builder().id(10L).build();
        destAccount = Account.builder().id(20L).build();

        lenient().when(userRepository.getReferenceById(1L)).thenReturn(user);
        lenient().when(accountService.findOwned(eq(1L), eq(10L))).thenReturn(account);
        lenient().when(accountService.findOwned(eq(1L), eq(20L))).thenReturn(destAccount);
        lenient().when(transactionMapper.toResponse(any())).thenReturn(mock(TransactionResponse.class));
    }

    // ─── create: balance delta ───────────────────────────────────────────────

    @Test
    void create_income_addsPositiveBalance() {
        BigDecimal amount = new BigDecimal("150.00");
        var req = new CreateTransactionRequest(TransactionType.INCOME, amount, LocalDate.now(), 10L, null, null, null, null);
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        transactionService.create(1L, req);

        verify(accountService).adjustBalance(10L, amount);
    }

    @Test
    void create_expense_subtractsBalance() {
        BigDecimal amount = new BigDecimal("50.00");
        var req = new CreateTransactionRequest(TransactionType.EXPENSE, amount, LocalDate.now(), 10L, null, null, null, null);
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        transactionService.create(1L, req);

        verify(accountService).adjustBalance(10L, amount.negate());
    }

    @Test
    void create_transfer_debitsSourceCreditsDest() {
        BigDecimal amount = new BigDecimal("200.00");
        var req = new CreateTransactionRequest(TransactionType.TRANSFER, amount, LocalDate.now(), 10L, 20L, null, null, null);
        when(transactionRepository.save(any())).thenAnswer(inv -> {
            Transaction tx = inv.getArgument(0);
            tx.setTransferAccount(destAccount);
            return tx;
        });

        transactionService.create(1L, req);

        verify(accountService).adjustBalance(10L, amount.negate());
        verify(accountService).adjustBalance(20L, amount);
    }

    @Test
    void create_transferWithNullDestination_throwsAndAdjustsNoBalance() {
        var req = new CreateTransactionRequest(TransactionType.TRANSFER, new BigDecimal("100.00"),
                LocalDate.now(), 10L, null, null, null, null);

        assertThatThrownBy(() -> transactionService.create(1L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transferAccountId");

        verify(accountService, never()).adjustBalance(anyLong(), any());
    }

    // ─── update: reverses old delta, applies new ──────────────────────────────

    @Test
    void update_amountChange_reversesOldAndAppliesNew() {
        BigDecimal oldAmount = new BigDecimal("100.00");
        BigDecimal newAmount = new BigDecimal("180.00");

        Transaction existing = Transaction.builder()
                .id(5L).account(account).transactionType(TransactionType.INCOME)
                .amount(oldAmount).transactionDate(LocalDate.now()).build();

        when(transactionRepository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(existing));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var req = new UpdateTransactionRequest(newAmount, null, null, null);
        transactionService.update(1L, 5L, req);

        // Reverse old INCOME: negate old amount
        verify(accountService).adjustBalance(10L, oldAmount.negate());
        // Apply new INCOME: add new amount
        verify(accountService).adjustBalance(10L, newAmount);
    }

    @Test
    void update_sameAmount_doesNotAdjustBalance() {
        BigDecimal amount = new BigDecimal("100.00");
        Transaction existing = Transaction.builder()
                .id(5L).account(account).transactionType(TransactionType.INCOME)
                .amount(amount).transactionDate(LocalDate.now()).build();

        when(transactionRepository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(existing));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Same amount — no balance change expected
        var req = new UpdateTransactionRequest(amount, null, null, null);
        transactionService.update(1L, 5L, req);

        verify(accountService, never()).adjustBalance(anyLong(), any());
    }

    // ─── delete: reverses full balance effect ─────────────────────────────────

    @Test
    void delete_income_reversesBalance() {
        BigDecimal amount = new BigDecimal("75.00");
        Transaction existing = Transaction.builder()
                .id(7L).account(account).transactionType(TransactionType.INCOME)
                .amount(amount).build();

        when(transactionRepository.findByIdAndUserId(7L, 1L)).thenReturn(Optional.of(existing));

        transactionService.delete(1L, 7L);

        verify(accountService).adjustBalance(10L, amount.negate());
        verify(transactionRepository).delete(existing);
    }

    @Test
    void delete_expense_reversesBalance() {
        BigDecimal amount = new BigDecimal("30.00");
        Transaction existing = Transaction.builder()
                .id(8L).account(account).transactionType(TransactionType.EXPENSE)
                .amount(amount).build();

        when(transactionRepository.findByIdAndUserId(8L, 1L)).thenReturn(Optional.of(existing));

        transactionService.delete(1L, 8L);

        // Reversing EXPENSE means adding back
        verify(accountService).adjustBalance(10L, amount);
        verify(transactionRepository).delete(existing);
    }
}
