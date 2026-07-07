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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
}
