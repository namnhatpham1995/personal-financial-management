package com.fintrack.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.support.ExchangeRateProviderTestStub;
import com.fintrack.support.HttpTestHelper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer coverage for /api/v1/analytics/overview, the seam service-level
 * AnalyticsService*Test classes never exercise (auth, query-param binding,
 * real cross-currency conversion via a stubbed ExchangeRateProvider, serialization).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@Import(ExchangeRateProviderTestStub.class)
class AnalyticsHttpIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_analytics_test")
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
        registry.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/fintrack_analytics_test_unused");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void overview_multiCurrency_convertsSpendingIntoTargetCurrency() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "analytics.overview@test.com");
        String usdAccount = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String vndAccount = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "VND");
        String categoryId = HttpTestHelper.createCategory(mockMvc, objectMapper, jwt, "Food", "EXPENSE");

        LocalDate today = LocalDate.now();
        HttpTestHelper.createExpenseTransaction(mockMvc, objectMapper, jwt, usdAccount, categoryId, "100.00", today.toString());
        // 3,000,000 VND at the stubbed rate (30000 VND per USD) converts to 100.00 USD
        HttpTestHelper.createExpenseTransaction(mockMvc, objectMapper, jwt, vndAccount, categoryId, "3000000", today.toString());

        MvcResult result = mockMvc.perform(get("/api/v1/analytics/overview")
                        .header("Authorization", "Bearer " + jwt)
                        .param("targetCurrency", "USD")
                        .param("from", today.minusDays(1).toString())
                        .param("to", today.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("targetCurrency").asText()).isEqualTo("USD");
        assertThat(body.get("ratesUnavailable").asBoolean()).isFalse();

        JsonNode spending = body.get("spending");
        assertThat(spending).hasSize(1);
        // 100.00 (USD, direct) + 100.0000 (VND converted) = 200.0000
        assertThat(new BigDecimal(spending.get(0).get("totalAmount").asText()))
                .isEqualByComparingTo(new BigDecimal("200.0000"));
        assertThat(spending.get(0).get("transactionCount").asLong()).isEqualTo(2L);
    }

    @Test
    void overview_withoutCredential_returns401() throws Exception {
        LocalDate today = LocalDate.now();
        mockMvc.perform(get("/api/v1/analytics/overview")
                        .param("targetCurrency", "USD")
                        .param("from", today.toString())
                        .param("to", today.toString()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * @RequestParam constraint violations (e.g. a malformed currency code) must surface as 400
     * via Spring MVC's native HandlerMethodValidationException, not fall through to the generic
     * 500 handler. Regression coverage for a bug where class-level @Validated on the controller
     * routed violations through Hibernate Validator's older AOP interceptor instead, which
     * throws ConstraintViolationException — a type GlobalExceptionHandler has no handler for.
     */
    @Test
    void overview_malformedTargetCurrency_returns400() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "analytics.badcurrency.overview@test.com");
        LocalDate today = LocalDate.now();

        mockMvc.perform(get("/api/v1/analytics/overview")
                        .header("Authorization", "Bearer " + jwt)
                        .param("targetCurrency", "not-a-currency")
                        .param("from", today.toString())
                        .param("to", today.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void budgetHistory_malformedCurrency_returns400() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "analytics.badcurrency.history@test.com");

        mockMvc.perform(get("/api/v1/analytics/budget-history")
                        .header("Authorization", "Bearer " + jwt)
                        .param("from", "2026-01-01")
                        .param("to", "2026-03-31")
                        .param("currency", "not-a-currency"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void balances_malformedTargetCurrency_returns400() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "analytics.badcurrency.balances@test.com");

        mockMvc.perform(get("/api/v1/analytics/balances")
                        .header("Authorization", "Bearer " + jwt)
                        .param("targetCurrency", "not-a-currency"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void budgetHistory_returnsEmptyPeriodsSeparatesCurrenciesAndAllowsReadPat() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "analytics.history@test.com");
        String eurAccount = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "EUR");
        String usdAccount = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String categoryId = HttpTestHelper.createCategory(
                mockMvc, objectMapper, jwt, "Historical groceries", "EXPENSE");

        createBudget(jwt, categoryId, "EUR", "500.00", "2026-01-01");
        createBudget(jwt, categoryId, "USD", "300.00", "2026-01-01");
        HttpTestHelper.createExpenseTransaction(
                mockMvc, objectMapper, jwt, eurAccount, categoryId, "125.00", "2026-01-15");
        HttpTestHelper.createExpenseTransaction(
                mockMvc, objectMapper, jwt, eurAccount, categoryId, "75.00", "2026-03-10");
        HttpTestHelper.createExpenseTransaction(
                mockMvc, objectMapper, jwt, usdAccount, categoryId, "40.00", "2026-01-20");

        JsonNode allRows = getBudgetHistory(jwt, null);
        assertThat(allRows).hasSize(6);
        assertHistoryRow(allRows, "EUR", "2026-01-01", "125.00");
        assertHistoryRow(allRows, "EUR", "2026-02-01", "0");
        assertHistoryRow(allRows, "EUR", "2026-03-01", "75.00");
        assertHistoryRow(allRows, "USD", "2026-01-01", "40.00");
        assertHistoryRow(allRows, "USD", "2026-02-01", "0");
        assertHistoryRow(allRows, "USD", "2026-03-01", "0");

        JsonNode eurRows = getBudgetHistory(jwt, "EUR");
        assertThat(eurRows).hasSize(3);
        assertThat(eurRows).allMatch(row -> row.get("currency").asText().equals("EUR"));

        String readPat = createReadPat(jwt);
        JsonNode patRows = getBudgetHistory(readPat, "USD");
        assertThat(patRows).hasSize(3);
        assertThat(patRows).allMatch(row -> row.get("currency").asText().equals("USD"));
    }

    private void createBudget(
            String jwt, String categoryId, String currency, String limit, String startDate) throws Exception {
        mockMvc.perform(post("/api/v1/budgets")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "categoryId", Long.parseLong(categoryId),
                                "period", "MONTHLY",
                                "amountLimit", limit,
                                "startDate", startDate,
                                "currency", currency))))
                .andExpect(status().isCreated());
    }

    private String createReadPat(String jwt) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/tokens")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Budget history test", "scope", "READ", "expiryDays", 30))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("plaintextToken").asText();
    }

    private JsonNode getBudgetHistory(String credential, String currency) throws Exception {
        var request = get("/api/v1/analytics/budget-history")
                .header("Authorization", "Bearer " + credential)
                .param("from", "2026-01-01")
                .param("to", "2026-03-31");
        if (currency != null) {
            request.param("currency", currency);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void assertHistoryRow(JsonNode rows, String currency, String periodStart, String spent) {
        JsonNode row = null;
        for (JsonNode candidate : rows) {
            if (candidate.get("currency").asText().equals(currency)
                    && candidate.get("periodStart").asText().equals(periodStart)) {
                row = candidate;
                break;
            }
        }
        assertThat(row).as("history row for %s in %s", currency, periodStart).isNotNull();
        assertThat(new BigDecimal(row.get("spent").asText())).isEqualByComparingTo(spent);
    }
}
