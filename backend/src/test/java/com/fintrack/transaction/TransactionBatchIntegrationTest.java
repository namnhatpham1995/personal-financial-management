package com.fintrack.transaction;

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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class TransactionBatchIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_batch").withUsername("test").withPassword("test");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl); r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword); r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/unused");
    }
    @Autowired MockMvc mockMvc; @Autowired ObjectMapper objectMapper;

    @Test void batchCreatesValidRowsSkipsDuplicatesAndReportsInvalidRows() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "batch@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "EUR");
        Map<String, Object> valid = Map.of("transactionType", "EXPENSE", "amount", "10.00", "transactionDate", "2026-01-01", "accountId", accountId, "importDedupKey", "batch-key-1");
        Map<String, Object> invalid = Map.of("transactionType", "EXPENSE", "amount", "5.00", "transactionDate", "2026-01-01", "accountId", 999999);
        JsonNode first = batch(jwt, List.of(valid, invalid));
        assertThat(first.get("results").get(0).get("status").asText()).isEqualTo("CREATED");
        assertThat(first.get("results").get(1).get("status").asText()).isEqualTo("FAILED");
        JsonNode second = batch(jwt, List.of(valid));
        assertThat(second.get("results").get(0).get("status").asText()).isEqualTo("SKIPPED_DUPLICATE");
    }

    private JsonNode batch(String token, List<Map<String, Object>> rows) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/transactions/batch").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("transactions", rows))))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
