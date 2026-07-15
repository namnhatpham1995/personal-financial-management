package com.fintrack.exchangerate;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer coverage for GET /api/v1/exchange-rates/convert, using the deterministic
 * ExchangeRateProviderTestStub (USD base, VND=30000, EUR=0.85) instead of live rates.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@Import(ExchangeRateProviderTestStub.class)
class ExchangeRateConvertHttpIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_convert_test")
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
        registry.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/fintrack_convert_test_unused");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void convert_eurToVnd_returnsConvertedAmountAndRate() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "convert.success@test.com");

        MvcResult result = mockMvc.perform(get("/api/v1/exchange-rates/convert")
                        .header("Authorization", "Bearer " + jwt)
                        .param("from", "EUR")
                        .param("to", "VND")
                        .param("amount", "500"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        // rate USD/VND=30000, USD/EUR=0.85 -> 500 EUR * (30000/0.85) ≈ 17,647,058.8235
        assertThat(node.get("from").asText()).isEqualTo("EUR");
        assertThat(node.get("to").asText()).isEqualTo("VND");
        assertThat(new BigDecimal(node.get("convertedAmount").asText()))
                .isCloseTo(new BigDecimal("17647058.8235"), org.assertj.core.data.Offset.offset(new BigDecimal("0.01")));
        assertThat(node.get("asOf").isNull()).isFalse();
    }

    @Test
    void convert_sameCurrency_isIdentityWithRateOne() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "convert.identity@test.com");

        MvcResult result = mockMvc.perform(get("/api/v1/exchange-rates/convert")
                        .header("Authorization", "Bearer " + jwt)
                        .param("from", "EUR")
                        .param("to", "EUR")
                        .param("amount", "500"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(new BigDecimal(node.get("convertedAmount").asText())).isEqualByComparingTo("500.0000");
        assertThat(new BigDecimal(node.get("rate").asText())).isEqualByComparingTo("1");
    }

    @Test
    void convert_unsupportedCurrency_returns400() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "convert.unsupported@test.com");

        mockMvc.perform(get("/api/v1/exchange-rates/convert")
                        .header("Authorization", "Bearer " + jwt)
                        .param("from", "EUR")
                        .param("to", "XXX")
                        .param("amount", "500"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void convert_invalidAmount_returns400() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "convert.badamount@test.com");

        mockMvc.perform(get("/api/v1/exchange-rates/convert")
                        .header("Authorization", "Bearer " + jwt)
                        .param("from", "EUR")
                        .param("to", "VND")
                        .param("amount", "-5"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void convert_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/exchange-rates/convert")
                        .param("from", "EUR")
                        .param("to", "VND")
                        .param("amount", "500"))
                .andExpect(status().isUnauthorized());
    }
}
