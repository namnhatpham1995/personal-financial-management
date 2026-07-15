package com.fintrack.transaction;

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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer coverage for cross-currency TRANSFER create/update/delete — the dual-amount
 * balance semantics that TransactionServiceTest exercises with mocks, verified here end-to-end
 * against a real Postgres balance column.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@Import(ExchangeRateProviderTestStub.class)
class CrossCurrencyTransferHttpIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_xfer_test")
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
        registry.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/fintrack_xfer_test_unused");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void createCrossCurrencyTransfer_adjustsBothBalancesWithTheirOwnAmounts() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "xfer.create@test.com");
        String eurAccount = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "EUR");
        String vndAccount = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "VND");

        MvcResult result = mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "accountId", eurAccount,
                                "transferAccountId", vndAccount,
                                "transactionType", "TRANSFER",
                                "amount", "500.00",
                                "destinationAmount", "14600000.00",
                                "transactionDate", "2026-06-01"))))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode created = objectMapper.readTree(result.getResponse().getContentAsString());
        // Source account starts at 0 balance, so this transfer legitimately trips the
        // negative-balance warning — assert only that the cross-currency-mismatch warning
        // (superseded by hard validation) is gone, not that warnings are empty.
        assertThat(created.get("warnings").isArray()).isTrue();
        for (JsonNode warning : created.get("warnings")) {
            assertThat(warning.get("code").asText())
                    .isNotEqualTo("currency_mismatch_or_unsupported_cross_currency_transfer");
        }
        assertThat(new BigDecimal(created.get("destinationAmount").asText()))
                .isEqualByComparingTo("14600000.00");

        assertThat(getBalance(jwt, eurAccount)).isEqualByComparingTo("-500.00");
        assertThat(getBalance(jwt, vndAccount)).isEqualByComparingTo("14600000.00");
    }

    @Test
    void createCrossCurrencyTransfer_withoutDestinationAmount_returns400() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "xfer.missing@test.com");
        String eurAccount = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "EUR");
        String vndAccount = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "VND");

        mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "accountId", eurAccount,
                                "transferAccountId", vndAccount,
                                "transactionType", "TRANSFER",
                                "amount", "500.00",
                                "transactionDate", "2026-06-01"))))
                .andExpect(status().isBadRequest());

        assertThat(getBalance(jwt, eurAccount)).isEqualByComparingTo("0.00");
        assertThat(getBalance(jwt, vndAccount)).isEqualByComparingTo("0.00");
    }

    @Test
    void sameCurrencyTransfer_withDestinationAmount_returns400() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "xfer.samecurrency@test.com");
        String usdAccount1 = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String usdAccount2 = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");

        mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "accountId", usdAccount1,
                                "transferAccountId", usdAccount2,
                                "transactionType", "TRANSFER",
                                "amount", "50.00",
                                "destinationAmount", "50.00",
                                "transactionDate", "2026-06-01"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteCrossCurrencyTransfer_restoresBothBalances() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "xfer.delete@test.com");
        String eurAccount = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "EUR");
        String vndAccount = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "VND");

        MvcResult created = mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "accountId", eurAccount,
                                "transferAccountId", vndAccount,
                                "transactionType", "TRANSFER",
                                "amount", "500.00",
                                "destinationAmount", "14600000.00",
                                "transactionDate", "2026-06-01"))))
                .andExpect(status().isCreated())
                .andReturn();
        String txId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(delete("/api/v1/transactions/" + txId)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNoContent());

        assertThat(getBalance(jwt, eurAccount)).isEqualByComparingTo("0.00");
        assertThat(getBalance(jwt, vndAccount)).isEqualByComparingTo("0.00");
    }

    @Test
    void updateCrossCurrencyTransfer_amountOnly_returns400AndLeavesBalancesUnchanged() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "xfer.update.onesided@test.com");
        String eurAccount = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "EUR");
        String vndAccount = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "VND");

        MvcResult created = mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "accountId", eurAccount,
                                "transferAccountId", vndAccount,
                                "transactionType", "TRANSFER",
                                "amount", "500.00",
                                "destinationAmount", "14600000.00",
                                "transactionDate", "2026-06-01"))))
                .andExpect(status().isCreated())
                .andReturn();
        String txId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(put("/api/v1/transactions/" + txId)
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", "600.00"))))
                .andExpect(status().isBadRequest());

        assertThat(getBalance(jwt, eurAccount)).isEqualByComparingTo("-500.00");
        assertThat(getBalance(jwt, vndAccount)).isEqualByComparingTo("14600000.00");
    }

    @Test
    void updateCrossCurrencyTransfer_bothAmounts_recalculatesBothBalances() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "xfer.update.twosided@test.com");
        String eurAccount = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "EUR");
        String vndAccount = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "VND");

        MvcResult created = mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "accountId", eurAccount,
                                "transferAccountId", vndAccount,
                                "transactionType", "TRANSFER",
                                "amount", "500.00",
                                "destinationAmount", "14600000.00",
                                "transactionDate", "2026-06-01"))))
                .andExpect(status().isCreated())
                .andReturn();
        String txId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(put("/api/v1/transactions/" + txId)
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "amount", "600.00", "destinationAmount", "17520000.00"))))
                .andExpect(status().isOk());

        assertThat(getBalance(jwt, eurAccount)).isEqualByComparingTo("-600.00");
        assertThat(getBalance(jwt, vndAccount)).isEqualByComparingTo("17520000.00");
    }

    private BigDecimal getBalance(String jwt, String accountId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/accounts/" + accountId)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        return new BigDecimal(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("currentBalance").asText());
    }
}
