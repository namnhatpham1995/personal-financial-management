package com.fintrack.analytics.repository;

import com.fintrack.account.domain.Account;
import com.fintrack.account.domain.AccountType;
import com.fintrack.account.repository.AccountRepository;
import com.fintrack.analytics.web.dto.SpendingByCategoryDto;
import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.category.domain.Category;
import com.fintrack.category.repository.CategoryRepository;
import com.fintrack.common.domain.TransactionType;
import com.fintrack.transaction.domain.Transaction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the account-scoped analytics queries used by the account detail view:
 * spending-by-category narrowed to one account (expense slices) and the incoming
 * transfer total (counterparty transfers).
 */
@DataJpaTest
@ActiveProfiles("test")
class AnalyticsRepositoryAccountScopeTest {

    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired AnalyticsRepository analyticsRepository; // JpaRepository<Transaction, Long>

    private static final LocalDate FROM = LocalDate.of(2025, 1, 1);
    private static final LocalDate TO = LocalDate.of(2025, 12, 31);
    private static final LocalDate DATE = LocalDate.of(2025, 6, 15);

    @Test
    void spendingByCategory_scopedToAccount_excludesOtherAccounts() {
        User user = user("scope-spend@test.com");
        Account a = account(user, "Account A");
        Account b = account(user, "Account B");
        Category food = category(user, "Food");

        expense(user, a, food, new BigDecimal("100.00"));
        expense(user, b, food, new BigDecimal("999.00")); // other account, must be excluded

        List<SpendingByCategoryDto> scoped =
                analyticsRepository.spendingByCategory(user.getId(), FROM, TO, a.getId());

        assertThat(scoped).hasSize(1);
        assertThat(scoped.get(0).categoryName()).isEqualTo("Food");
        assertThat(scoped.get(0).total()).isEqualByComparingTo(new BigDecimal("100.00"));

        List<SpendingByCategoryDto> overall =
                analyticsRepository.spendingByCategory(user.getId(), FROM, TO, null);
        assertThat(overall).hasSize(1);
        assertThat(overall.get(0).total()).isEqualByComparingTo(new BigDecimal("1099.00"));
    }

    @Test
    void incomingTransferTotal_sumsOnlyCounterpartyTransfers() {
        User user = user("scope-incoming@test.com");
        Account a = account(user, "Account A");
        Account b = account(user, "Account B");

        transfer(user, b, a, new BigDecimal("300.00")); // into A — counted
        transfer(user, a, b, new BigDecimal("50.00"));   // out of A — not counted

        BigDecimal incomingToA =
                analyticsRepository.incomingTransferTotal(user.getId(), a.getId(), FROM, TO);
        assertThat(incomingToA).isEqualByComparingTo(new BigDecimal("300.00"));

        BigDecimal incomingToB =
                analyticsRepository.incomingTransferTotal(user.getId(), b.getId(), FROM, TO);
        assertThat(incomingToB).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private User user(String email) {
        return userRepository.save(User.builder()
                .email(email).passwordHash("h").fullName("Test User").build());
    }

    private Account account(User user, String name) {
        return accountRepository.save(Account.builder()
                .user(user).name(name).accountType(AccountType.CASH)
                .currency("USD").currentBalance(BigDecimal.ZERO).build());
    }

    private Category category(User user, String name) {
        return categoryRepository.save(Category.builder()
                .user(user).name(name).transactionType(TransactionType.EXPENSE).build());
    }

    private void expense(User user, Account account, Category category, BigDecimal amount) {
        analyticsRepository.save(Transaction.builder()
                .user(user).account(account).category(category)
                .transactionType(TransactionType.EXPENSE)
                .amount(amount).transactionDate(DATE).build());
    }

    private void transfer(User user, Account source, Account dest, BigDecimal amount) {
        analyticsRepository.save(Transaction.builder()
                .user(user).account(source).transferAccount(dest)
                .transactionType(TransactionType.TRANSFER)
                .amount(amount).transactionDate(DATE).build());
    }
}
