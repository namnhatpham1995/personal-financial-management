package com.fintrack.account.repository;

import com.fintrack.account.domain.Account;
import com.fintrack.account.domain.AccountType;
import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class AccountRepositoryTest {

    @Autowired AccountRepository accountRepository;
    @Autowired UserRepository userRepository;

    @Test
    void findAllByUserId_returnsOnlyOwnerAccounts() {
        User alice = userRepository.save(User.builder()
                .email("alice@test.com").passwordHash("h").fullName("Alice A").build());
        User bob = userRepository.save(User.builder()
                .email("bob@test.com").passwordHash("h").fullName("Bob B").build());

        accountRepository.save(Account.builder()
                .user(alice).name("Alice Cash").accountType(AccountType.CASH)
                .currency("USD").currentBalance(BigDecimal.TEN).build());
        accountRepository.save(Account.builder()
                .user(bob).name("Bob Bank").accountType(AccountType.BANK)
                .currency("USD").currentBalance(BigDecimal.ONE).build());

        List<Account> aliceAccounts = accountRepository.findAllByUserId(alice.getId());

        assertThat(aliceAccounts).hasSize(1);
        assertThat(aliceAccounts.get(0).getName()).isEqualTo("Alice Cash");
    }

    @Test
    void findByIdAndUserId_wrongUser_returnsEmpty() {
        User alice = userRepository.save(User.builder()
                .email("alice2@test.com").passwordHash("h").fullName("Alice 2").build());
        Account acc = accountRepository.save(Account.builder()
                .user(alice).name("My Account").accountType(AccountType.SAVINGS)
                .currency("USD").currentBalance(BigDecimal.ZERO).build());

        assertThat(accountRepository.findByIdAndUserId(acc.getId(), 9999L)).isEmpty();
    }
}
