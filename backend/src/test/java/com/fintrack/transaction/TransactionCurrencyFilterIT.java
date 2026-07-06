package com.fintrack.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.auth.web.dto.TokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Integration coverage for the `currency` filter on GET /api/v1/transactions. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class TransactionCurrencyFilterIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_currency_filter")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/unused");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private String registerAndGetToken(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email, "password", "pass1234",
                                "firstName", "Test", "lastName", "User"))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), TokenResponse.class)
                .accessToken();
    }

    private Long createAccount(String token, String currency, String initialBalance) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", currency + " Account",
                                "accountType", "CASH",
                                "currency", currency,
                                "initialBalance", initialBalance))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private void createTransaction(String token, Long accountId, String type, String amount, String date) throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "accountId", accountId,
                                "transactionType", type,
                                "amount", amount,
                                "transactionDate", date))))
                .andExpect(status().isCreated());
    }

    @Test
    void currencyFilter_returnsOnlyTransactionsForAccountsInThatCurrency() throws Exception {
        String token = registerAndGetToken("currency-filter@test.com");
        Long eurAccount = createAccount(token, "EUR", "500.00");
        Long usdAccount = createAccount(token, "USD", "500.00");

        createTransaction(token, eurAccount, "EXPENSE", "20.00", "2026-06-01");
        createTransaction(token, eurAccount, "INCOME", "40.00", "2026-06-02");
        createTransaction(token, usdAccount, "EXPENSE", "15.00", "2026-06-01");

        mockMvc.perform(get("/api/v1/transactions")
                        .header("Authorization", "Bearer " + token)
                        .param("currency", "EUR"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
                    assertThat(content).hasSize(2);
                    content.forEach(node -> assertThat(node.get("currency").asText()).isEqualTo("EUR"));
                });
    }

    @Test
    void currencyFilter_composesWithTypeAndPaginationAndSort() throws Exception {
        String token = registerAndGetToken("currency-filter-compose@test.com");
        Long eurAccount = createAccount(token, "EUR", "0.00");

        for (int i = 1; i <= 8; i++) {
            createTransaction(token, eurAccount, "EXPENSE", "10.00", "2026-06-0" + i);
        }
        createTransaction(token, eurAccount, "INCOME", "999.00", "2026-06-09");

        MvcResult result = mockMvc.perform(get("/api/v1/transactions")
                        .header("Authorization", "Bearer " + token)
                        .param("currency", "EUR")
                        .param("type", "EXPENSE")
                        .param("size", "5")
                        .param("sortBy", "transactionDate")
                        .param("sortDir", "desc"))
                .andExpect(status().isOk())
                .andReturn();

        var body = objectMapper.readTree(result.getResponse().getContentAsString());
        var content = body.get("content");
        assertThat(content).hasSize(5);
        assertThat(body.get("totalElements").asInt()).isEqualTo(8);
        content.forEach(node -> assertThat(node.get("transactionType").asText()).isEqualTo("EXPENSE"));
        // Most recent date (06-08) must be first under desc sort
        assertThat(content.get(0).get("transactionDate").asText()).isEqualTo("2026-06-08");
    }

    @Test
    void currencyFilter_isScopedToOwningUser() throws Exception {
        String tokenA = registerAndGetToken("currency-filter-user-a@test.com");
        String tokenB = registerAndGetToken("currency-filter-user-b@test.com");

        Long accountA = createAccount(tokenA, "EUR", "0.00");
        Long accountB = createAccount(tokenB, "EUR", "0.00");
        createTransaction(tokenA, accountA, "EXPENSE", "10.00", "2026-06-01");
        createTransaction(tokenB, accountB, "EXPENSE", "10.00", "2026-06-01");

        mockMvc.perform(get("/api/v1/transactions")
                        .header("Authorization", "Bearer " + tokenA)
                        .param("currency", "EUR"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
                    assertThat(content).hasSize(1);
                });
    }
}
