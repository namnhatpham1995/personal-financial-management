package com.fintrack.account.service;

import com.fintrack.account.domain.Account;
import com.fintrack.account.domain.AccountType;
import com.fintrack.account.mapper.AccountMapper;
import com.fintrack.account.repository.AccountRepository;
import com.fintrack.account.web.dto.AccountResponse;
import com.fintrack.account.web.dto.CreateAccountRequest;
import com.fintrack.account.web.dto.UpdateAccountRequest;
import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.common.cache.CacheVersionService;
import com.fintrack.common.domain.TransactionType;
import com.fintrack.exchangerate.service.ExchangeRateService;
import com.fintrack.transaction.domain.Transaction;
import com.fintrack.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock UserRepository userRepository;
    @Mock AccountMapper accountMapper;
    @Mock ExchangeRateService exchangeRateService;
    @Mock CacheVersionService cacheVersionService;

    @InjectMocks AccountService accountService;

    private static final Long USER_ID = 1L;
    private static final Long ACCOUNT_ID = 10L;

    private Account account;

    @BeforeEach
    void setUp() {
        account = Account.builder()
                .id(ACCOUNT_ID)
                .name("Checking")
                .accountType(AccountType.BANK)
                .currency("USD")
                .initialBalance(new BigDecimal("100.00"))
                .currentBalance(new BigDecimal("150.00"))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    // ── Currency validation on create ────────────────────────────────────────────

    @Test
    void create_withSupportedCurrency_succeeds() {
        CreateAccountRequest req = new CreateAccountRequest("Checking", AccountType.BANK, "USD", BigDecimal.ZERO);
        User user = new User();

        when(exchangeRateService.supportedCurrencies()).thenReturn(Set.of("USD", "EUR", "VND"));
        when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
        when(accountMapper.toEntity(req)).thenReturn(account);
        when(accountRepository.save(any())).thenReturn(account);
        when(accountMapper.toResponse(any())).thenReturn(stubResponse());

        var result = accountService.create(USER_ID, req);

        assertThat(result).isNotNull();
        verify(exchangeRateService).supportedCurrencies();
    }

    @Test
    void create_withUnsupportedCurrency_throws400() {
        CreateAccountRequest req = new CreateAccountRequest("Checking", AccountType.BANK, "XXX", BigDecimal.ZERO);

        when(exchangeRateService.supportedCurrencies()).thenReturn(Set.of("USD", "EUR", "VND"));

        assertThatThrownBy(() -> accountService.create(USER_ID, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported currency: XXX");
    }

    @Test
    void create_withEmptyCacheSupported_allowsThrough() {
        // Empty cache returns seed fallback; "USD" is in seed so it passes
        CreateAccountRequest req = new CreateAccountRequest("Checking", AccountType.BANK, "USD", BigDecimal.ZERO);
        User user = new User();

        // Seed fallback set from ExchangeRateService — simulate empty cache returning seed
        when(exchangeRateService.supportedCurrencies()).thenReturn(Set.of("USD", "EUR", "VND", "GBP"));
        when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
        when(accountMapper.toEntity(req)).thenReturn(account);
        when(accountRepository.save(any())).thenReturn(account);
        when(accountMapper.toResponse(any())).thenReturn(stubResponse());

        var result = accountService.create(USER_ID, req);
        assertThat(result).isNotNull();
    }

    // ── Currency validation on update ────────────────────────────────────────────

    @Test
    void update_withSupportedCurrencyChange_succeeds() {
        UpdateAccountRequest req = new UpdateAccountRequest(null, null, "EUR", null);

        when(exchangeRateService.supportedCurrencies()).thenReturn(Set.of("USD", "EUR", "VND"));
        when(accountRepository.findByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenReturn(account);
        when(accountMapper.toResponse(any())).thenReturn(stubResponse());
        doNothing().when(accountMapper).updateEntity(any(), any());

        var result = accountService.update(USER_ID, ACCOUNT_ID, req);
        assertThat(result).isNotNull();
    }

    @Test
    void update_withUnsupportedCurrencyChange_throws400() {
        UpdateAccountRequest req = new UpdateAccountRequest(null, null, "XXX", null);

        when(exchangeRateService.supportedCurrencies()).thenReturn(Set.of("USD", "EUR", "VND"));

        assertThatThrownBy(() -> accountService.update(USER_ID, ACCOUNT_ID, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported currency: XXX");
    }

    // ── Task 1.4: update with initialBalance recomputes current balance ─────────

    @Test
    void update_withInitialBalance_recomputesCurrentBalance() {
        BigDecimal newInitial = new BigDecimal("200.00");
        BigDecimal txNet = new BigDecimal("50.00");
        UpdateAccountRequest req = new UpdateAccountRequest(null, null, null, newInitial);

        when(accountRepository.findByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(accountRepository.computeBalanceFromTransactions(ACCOUNT_ID)).thenReturn(txNet);
        when(accountRepository.save(any())).thenReturn(account);
        when(accountMapper.toResponse(any())).thenReturn(stubResponse());
        // Simulate what the real mapper does: set initialBalance on the entity
        doAnswer(inv -> { account.setInitialBalance(newInitial); return null; })
                .when(accountMapper).updateEntity(any(), any());

        accountService.update(USER_ID, ACCOUNT_ID, req);

        // current = new initial (200) + txNet (50) = 250
        assertThat(account.getCurrentBalance()).isEqualByComparingTo(new BigDecimal("250.00"));
        verify(accountRepository).computeBalanceFromTransactions(ACCOUNT_ID);
    }

    @Test
    void update_withoutInitialBalance_doesNotRecompute() {
        UpdateAccountRequest req = new UpdateAccountRequest("New Name", null, null, null);

        when(accountRepository.findByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenReturn(account);
        when(accountMapper.toResponse(any())).thenReturn(stubResponse());
        doNothing().when(accountMapper).updateEntity(any(), any());

        accountService.update(USER_ID, ACCOUNT_ID, req);

        verify(accountRepository, never()).computeBalanceFromTransactions(any());
    }

    @Test
    void update_currencyChange_doesNotRecompute() {
        UpdateAccountRequest req = new UpdateAccountRequest(null, null, "EUR", null);

        when(exchangeRateService.supportedCurrencies()).thenReturn(Set.of("USD", "EUR", "VND"));
        when(accountRepository.findByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenReturn(account);
        when(accountMapper.toResponse(any())).thenReturn(stubResponse());
        doNothing().when(accountMapper).updateEntity(any(), any());

        accountService.update(USER_ID, ACCOUNT_ID, req);

        verify(accountRepository, never()).computeBalanceFromTransactions(any());
    }

    // ── Task 2.5: cascade delete reverses counterparty transfer balances ─────────

    @Test
    void delete_noTransactions_deletesAccountOnly() {
        when(accountRepository.findByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(transactionRepository.findConnectedToAccount(ACCOUNT_ID)).thenReturn(List.of());

        accountService.delete(USER_ID, ACCOUNT_ID);

        verify(transactionRepository).deleteAll(List.of());
        verify(accountRepository).delete(account);
    }

    @Test
    void delete_withTransferWhereAccountIsSource_reversesDest() {
        Account dest = Account.builder().id(20L).name("Savings").accountType(AccountType.SAVINGS)
                .currency("USD").currentBalance(new BigDecimal("500.00")).build();

        Transaction transfer = Transaction.builder()
                .id(1L)
                .transactionType(TransactionType.TRANSFER)
                .amount(new BigDecimal("100.00"))
                .account(account)
                .transferAccount(dest)
                .build();

        when(accountRepository.findByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(transactionRepository.findConnectedToAccount(ACCOUNT_ID)).thenReturn(List.of(transfer));
        when(accountRepository.existsById(20L)).thenReturn(true);

        accountService.delete(USER_ID, ACCOUNT_ID);

        // dest had 100 applied; reversal should atomically subtract 100
        verify(accountRepository).atomicAdjustBalance(20L, new BigDecimal("100.00").negate());
        verify(transactionRepository).deleteAll(List.of(transfer));
        verify(accountRepository).delete(account);
    }

    @Test
    void delete_withTransferWhereAccountIsDest_restoresSource() {
        Account source = Account.builder().id(30L).name("Wallet").accountType(AccountType.CASH)
                .currency("USD").currentBalance(new BigDecimal("300.00")).build();

        Transaction transfer = Transaction.builder()
                .id(2L)
                .transactionType(TransactionType.TRANSFER)
                .amount(new BigDecimal("75.00"))
                .account(source)
                .transferAccount(account)
                .build();

        when(accountRepository.findByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(transactionRepository.findConnectedToAccount(ACCOUNT_ID)).thenReturn(List.of(transfer));
        when(accountRepository.existsById(30L)).thenReturn(true);

        accountService.delete(USER_ID, ACCOUNT_ID);

        // source had 75 deducted; restore it atomically
        verify(accountRepository).atomicAdjustBalance(30L, new BigDecimal("75.00"));
        verify(transactionRepository).deleteAll(List.of(transfer));
        verify(accountRepository).delete(account);
    }

    // ── Task 2.5: delete preview ─────────────────────────────────────────────────

    @Test
    void getDeletePreview_returnsConnectedCount() {
        when(accountRepository.findByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(accountRepository.countConnectedTransactions(ACCOUNT_ID)).thenReturn(5L);

        var preview = accountService.getDeletePreview(USER_ID, ACCOUNT_ID);

        assertThat(preview.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(preview.transactionCount()).isEqualTo(5L);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private AccountResponse stubResponse() {
        return new AccountResponse(ACCOUNT_ID, "Checking", AccountType.BANK, "USD",
                BigDecimal.TEN, BigDecimal.TEN, Instant.now(), Instant.now());
    }
}
