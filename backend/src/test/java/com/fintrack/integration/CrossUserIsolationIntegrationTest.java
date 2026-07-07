package com.fintrack.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.support.HttpTestHelper;
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

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies cross-user data isolation at the HTTP layer, independent of any
 * service-level ownership checks. This is the security-critical test: a bug that
 * bypasses service-level scoping (e.g. a controller calling a repository directly)
 * would still be caught here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class CrossUserIsolationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_isolation_test")
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
        registry.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/fintrack_isolation_test_unused");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private static boolean isDenied(int status) {
        return status == 404 || status == 403;
    }

    // ── Accounts ─────────────────────────────────────────────────────────────

    @Test
    void userB_cannotReadUserA_account() throws Exception {
        String jwtA = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idor.acct.a@test.com");
        String jwtB = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idor.acct.b@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwtA, "USD");

        MvcResult result = mockMvc.perform(get("/api/v1/accounts/" + accountId)
                        .header("Authorization", "Bearer " + jwtB))
                .andReturn();
        assertThat(isDenied(result.getResponse().getStatus())).isTrue();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("USD Account");
    }

    @Test
    void userB_cannotMutateUserA_account_andAccountIsUnchanged() throws Exception {
        String jwtA = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idor.acct.mut.a@test.com");
        String jwtB = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idor.acct.mut.b@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwtA, "USD");

        MvcResult putResult = mockMvc.perform(put("/api/v1/accounts/" + accountId)
                        .header("Authorization", "Bearer " + jwtB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Hijacked", "accountType", "BANK", "currency", "USD"))))
                .andReturn();
        assertThat(isDenied(putResult.getResponse().getStatus())).isTrue();

        MvcResult deleteResult = mockMvc.perform(delete("/api/v1/accounts/" + accountId)
                        .header("Authorization", "Bearer " + jwtB))
                .andReturn();
        assertThat(isDenied(deleteResult.getResponse().getStatus())).isTrue();

        // Re-read as A: unchanged and still exists
        mockMvc.perform(get("/api/v1/accounts/" + accountId).header("Authorization", "Bearer " + jwtA))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.name").value("USD Account"));
    }

    // ── Transactions ─────────────────────────────────────────────────────────

    @Test
    void userB_cannotReadOrMutateUserA_transaction() throws Exception {
        String jwtA = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idor.tx.a@test.com");
        String jwtB = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idor.tx.b@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwtA, "USD");
        String categoryId = HttpTestHelper.createCategory(mockMvc, objectMapper, jwtA, "Groceries", "EXPENSE");
        String txId = HttpTestHelper.createExpenseTransaction(
                mockMvc, objectMapper, jwtA, accountId, categoryId, "50.00", LocalDate.now().toString());

        MvcResult getResult = mockMvc.perform(get("/api/v1/transactions/" + txId)
                        .header("Authorization", "Bearer " + jwtB))
                .andReturn();
        assertThat(isDenied(getResult.getResponse().getStatus())).isTrue();

        MvcResult deleteResult = mockMvc.perform(delete("/api/v1/transactions/" + txId)
                        .header("Authorization", "Bearer " + jwtB))
                .andReturn();
        assertThat(isDenied(deleteResult.getResponse().getStatus())).isTrue();

        // Re-read as A: still exists
        mockMvc.perform(get("/api/v1/transactions/" + txId).header("Authorization", "Bearer " + jwtA))
                .andExpect(status().isOk());
    }

    // ── Budgets ──────────────────────────────────────────────────────────────

    @Test
    void userB_cannotReadOrMutateUserA_budget() throws Exception {
        String jwtA = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idor.budget.a@test.com");
        String jwtB = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idor.budget.b@test.com");
        String categoryId = HttpTestHelper.createCategory(mockMvc, objectMapper, jwtA, "Entertainment", "EXPENSE");

        MvcResult createResult = mockMvc.perform(post("/api/v1/budgets")
                        .header("Authorization", "Bearer " + jwtA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "categoryId", Long.parseLong(categoryId),
                                "period", "MONTHLY",
                                "amountLimit", "200.00",
                                "startDate", LocalDate.now().withDayOfMonth(1).toString(),
                                "currency", "USD"))))
                .andExpect(status().isCreated())
                .andReturn();
        String budgetId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        MvcResult getResult = mockMvc.perform(get("/api/v1/budgets/" + budgetId)
                        .header("Authorization", "Bearer " + jwtB))
                .andReturn();
        assertThat(isDenied(getResult.getResponse().getStatus())).isTrue();

        MvcResult deleteResult = mockMvc.perform(delete("/api/v1/budgets/" + budgetId)
                        .header("Authorization", "Bearer " + jwtB))
                .andReturn();
        assertThat(isDenied(deleteResult.getResponse().getStatus())).isTrue();

        // Re-read as A: still exists
        mockMvc.perform(get("/api/v1/budgets/" + budgetId).header("Authorization", "Bearer " + jwtA))
                .andExpect(status().isOk());
    }
}
