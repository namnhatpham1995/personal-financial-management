package com.fintrack.budget.repository;

import com.fintrack.account.domain.Account;
import com.fintrack.account.domain.AccountType;
import com.fintrack.account.repository.AccountRepository;
import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.budget.domain.Budget;
import com.fintrack.budget.domain.BudgetPeriod;
import com.fintrack.category.domain.Category;
import com.fintrack.category.repository.CategoryRepository;
import com.fintrack.common.domain.TransactionType;
import com.fintrack.transaction.domain.Transaction;
import com.fintrack.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that sumSpentInPeriod filters by account currency, so a USD budget
 * only counts USD-account transactions and a VND budget only counts VND-account
 * transactions — even when both share the same category and user.
 */
@DataJpaTest
@ActiveProfiles("test")
class BudgetRepositoryCurrencyTest {

    @Autowired BudgetRepository budgetRepository;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired TransactionRepository transactionRepository;

    private User user;
    private Account usdAccount;
    private Account vndAccount;
    private Category shoppingCategory;

    private final LocalDate periodStart = LocalDate.now().withDayOfMonth(1);
    private final LocalDate periodEnd   = LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.builder()
                .email("budget-currency-test@example.com")
                .passwordHash("hashed")
                .fullName("Test User")
                .build());

        usdAccount = accountRepository.save(Account.builder()
                .user(user).name("USD Checking")
                .accountType(AccountType.BANK).currency("USD")
                .currentBalance(BigDecimal.ZERO).build());

        vndAccount = accountRepository.save(Account.builder()
                .user(user).name("VND Savings")
                .accountType(AccountType.BANK).currency("VND")
                .currentBalance(BigDecimal.ZERO).build());

        shoppingCategory = categoryRepository.save(Category.builder()
                .user(user).name("Shopping")
                .transactionType(TransactionType.EXPENSE)
                .build());

        // USD expense: 200.00
        transactionRepository.save(Transaction.builder()
                .user(user).account(usdAccount).category(shoppingCategory)
                .transactionType(TransactionType.EXPENSE)
                .amount(new BigDecimal("200.00"))
                .transactionDate(LocalDate.now())
                .build());

        // Another USD expense: 150.00 — total USD = 350.00
        transactionRepository.save(Transaction.builder()
                .user(user).account(usdAccount).category(shoppingCategory)
                .transactionType(TransactionType.EXPENSE)
                .amount(new BigDecimal("150.00"))
                .transactionDate(LocalDate.now())
                .build());

        // VND expense: 1_000_000
        transactionRepository.save(Transaction.builder()
                .user(user).account(vndAccount).category(shoppingCategory)
                .transactionType(TransactionType.EXPENSE)
                .amount(new BigDecimal("1000000.00"))
                .transactionDate(LocalDate.now())
                .build());
    }

    @Test
    void sumSpentInPeriod_usdCurrency_returnsOnlyUsdExpenses() {
        BigDecimal usdSpent = budgetRepository.sumSpentInPeriod(
                user.getId(), shoppingCategory.getId(), periodStart, periodEnd, "USD");

        assertThat(usdSpent).isEqualByComparingTo("350.00");
    }

    @Test
    void sumSpentInPeriod_vndCurrency_returnsOnlyVndExpenses() {
        BigDecimal vndSpent = budgetRepository.sumSpentInPeriod(
                user.getId(), shoppingCategory.getId(), periodStart, periodEnd, "VND");

        assertThat(vndSpent).isEqualByComparingTo("1000000.00");
    }

    @Test
    void sumSpentInPeriod_eurCurrency_returnsZeroWhenNoEurTransactions() {
        BigDecimal eurSpent = budgetRepository.sumSpentInPeriod(
                user.getId(), shoppingCategory.getId(), periodStart, periodEnd, "EUR");

        assertThat(eurSpent).isEqualByComparingTo("0");
    }

    @Test
    void existsByUserIdAndCategoryIdAndPeriodAndCurrency_detectsDuplicate() {
        // Save a USD budget
        budgetRepository.save(Budget.builder()
                .user(user).category(shoppingCategory)
                .amountLimit(new BigDecimal("500.00"))
                .period(BudgetPeriod.MONTHLY)
                .startDate(periodStart)
                .currency("USD")
                .build());

        assertThat(budgetRepository.existsByUserIdAndCategoryIdAndPeriodAndCurrency(
                user.getId(), shoppingCategory.getId(), BudgetPeriod.MONTHLY, "USD"))
                .isTrue();

        // VND budget does NOT exist yet → no conflict
        assertThat(budgetRepository.existsByUserIdAndCategoryIdAndPeriodAndCurrency(
                user.getId(), shoppingCategory.getId(), BudgetPeriod.MONTHLY, "VND"))
                .isFalse();
    }
}
