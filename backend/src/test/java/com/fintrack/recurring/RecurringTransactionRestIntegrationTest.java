package com.fintrack.recurring;

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

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer round-trip for the recurring-transaction REST endpoints. The API
 * exposes pause/resume as its mutating actions — there is no update endpoint.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class RecurringTransactionRestIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_recurring_rest_test")
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
        registry.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/fintrack_recurring_rest_test_unused");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void recurringDefinition_roundTripsThroughRestApi() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "recurring.rest@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String categoryId = HttpTestHelper.createCategory(mockMvc, objectMapper, jwt, "Subscriptions", "EXPENSE");

        MvcResult createResult = mockMvc.perform(post("/api/v1/recurring-transactions")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "transactionType", "EXPENSE",
                                "amount", "9.99",
                                "accountId", Long.parseLong(accountId),
                                "categoryId", Long.parseLong(categoryId),
                                "frequency", "MONTHLY",
                                "intervalValue", 1,
                                "startDate", LocalDate.now().toString()))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String recurringId = created.get("id").asText();
        assertThat(created.get("active").asBoolean()).isTrue();

        // List: definition appears
        MvcResult listResult = mockMvc.perform(get("/api/v1/recurring-transactions")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode list = objectMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("id").asText()).isEqualTo(recurringId);

        // Pause: active becomes false
        MvcResult pauseResult = mockMvc.perform(post("/api/v1/recurring-transactions/" + recurringId + "/pause")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(pauseResult.getResponse().getContentAsString()).get("active").asBoolean()).isFalse();

        // Resume: active becomes true again
        MvcResult resumeResult = mockMvc.perform(post("/api/v1/recurring-transactions/" + recurringId + "/resume")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(resumeResult.getResponse().getContentAsString()).get("active").asBoolean()).isTrue();

        // Delete: gone from subsequent list calls
        mockMvc.perform(delete("/api/v1/recurring-transactions/" + recurringId)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNoContent());

        MvcResult listAfterDelete = mockMvc.perform(get("/api/v1/recurring-transactions")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(listAfterDelete.getResponse().getContentAsString())).isEmpty();
    }
}
