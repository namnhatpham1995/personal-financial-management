package com.fintrack.transaction.mapper;

import com.fintrack.account.domain.Account;
import com.fintrack.account.domain.AccountType;
import com.fintrack.common.domain.TransactionType;
import com.fintrack.transaction.domain.Transaction;
import com.fintrack.transaction.web.dto.TransactionResponse;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionMapperCurrencyTest {

    private final TransactionMapper mapper = Mappers.getMapper(TransactionMapper.class);

    // Task 3.3: transaction response carries owning account currency

    @Test
    void toResponse_includesAccountCurrency() {
        Account usdAccount = Account.builder()
                .id(1L).name("USD Bank").accountType(AccountType.BANK)
                .currency("USD").currentBalance(BigDecimal.TEN).initialBalance(BigDecimal.ZERO)
                .build();

        Transaction tx = Transaction.builder()
                .id(100L)
                .transactionType(TransactionType.EXPENSE)
                .amount(new BigDecimal("50.00"))
                .transactionDate(LocalDate.of(2025, 6, 1))
                .account(usdAccount)
                .build();

        TransactionResponse response = mapper.toResponse(tx);

        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.accountId()).isEqualTo(1L);
        assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    void toResponse_eurAccount_carriesEurCurrency() {
        Account eurAccount = Account.builder()
                .id(2L).name("EUR Savings").accountType(AccountType.SAVINGS)
                .currency("EUR").currentBalance(BigDecimal.TEN).initialBalance(BigDecimal.ZERO)
                .build();

        Transaction tx = Transaction.builder()
                .id(101L)
                .transactionType(TransactionType.INCOME)
                .amount(new BigDecimal("200.00"))
                .transactionDate(LocalDate.of(2025, 6, 1))
                .account(eurAccount)
                .build();

        TransactionResponse response = mapper.toResponse(tx);

        assertThat(response.currency()).isEqualTo("EUR");
    }
}
