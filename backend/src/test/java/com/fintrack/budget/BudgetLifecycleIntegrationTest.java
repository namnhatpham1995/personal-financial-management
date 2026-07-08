package com.fintrack.budget;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-module HTTP test: budget + transaction + analytics/budget-progress.
 *
 * AnalyticsService.getBudgetProgress -> sumSpentInPeriod filters spend by
 * account currency matching the budget's own currency; there is no cross-currency
 * conversion in this path (confirmed via BudgetRepositoryCurrencyTest). So this
 * test verifies same-currency aggregation AND that a second budget in a different
 * currency on the same category does not bleed into the first's totals.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class BudgetLifecycleIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_budget_test")
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
        registry.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/fintrack_budget_test_unused");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private String createBudget(String jwt, String categoryId, String currency, String limit) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/budgets")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "categoryId", Long.parseLong(categoryId),
                                "period", "MONTHLY",
                                "amountLimit", limit,
                                "startDate", LocalDate.now().withDayOfMonth(1).toString(),
                                "currency", currency))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private JsonNode getBudgetProgress(String jwt, String budgetId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/analytics/budget-progress")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode all = objectMapper.readTree(result.getResponse().getContentAsString());
        for (JsonNode node : all) {
            if (node.get("budgetId").asText().equals(budgetId)) return node;
        }
        throw new AssertionError("Budget " + budgetId + " not found in progress response: " + all);
    }

    @Test
    void budgetProgress_reflectsSameCurrencySpending_andIsUnaffectedByOtherCurrencyBudget() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "budget.lifecycle@test.com");
        String usdAccount = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String vndAccount = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "VND");
        String categoryId = HttpTestHelper.createCategory(mockMvc, objectMapper, jwt, "Shopping", "EXPENSE");

        String usdBudgetId = createBudget(jwt, categoryId, "USD", "500.00");
        LocalDate today = LocalDate.now();
        HttpTestHelper.createExpenseTransaction(mockMvc, objectMapper, jwt, usdAccount, categoryId, "120.00", today.toString());
        HttpTestHelper.createExpenseTransaction(mockMvc, objectMapper, jwt, usdAccount, categoryId, "30.00", today.toString());

        JsonNode usdProgress = getBudgetProgress(jwt, usdBudgetId);
        assertThat(new BigDecimal(usdProgress.get("spent").asText())).isEqualByComparingTo("150.00");
        assertThat(new BigDecimal(usdProgress.get("remaining").asText())).isEqualByComparingTo("350.00");
        assertThat(usdProgress.get("overBudget").asBoolean()).isFalse();

        // Second budget, same category, different currency — must not affect the USD budget's totals
        String vndBudgetId = createBudget(jwt, categoryId, "VND", "10000000");
        HttpTestHelper.createExpenseTransaction(mockMvc, objectMapper, jwt, vndAccount, categoryId, "2000000", today.toString());

        JsonNode usdProgressAfter = getBudgetProgress(jwt, usdBudgetId);
        assertThat(new BigDecimal(usdProgressAfter.get("spent").asText())).isEqualByComparingTo("150.00");

        JsonNode vndProgress = getBudgetProgress(jwt, vndBudgetId);
        assertThat(new BigDecimal(vndProgress.get("spent").asText())).isEqualByComparingTo("2000000");
    }
}
