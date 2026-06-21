package com.fintrack.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.auth.web.dto.TokenResponse;
import com.fintrack.audit.domain.AuditLogRepository;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FintrackIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_test")
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
        // MongoDB intentionally not configured — verifies audit works without Mongo (task 2.2)
        registry.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/fintrack_test_unused");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AuditLogRepository auditLogRepository;

    static String accessToken;

    @Test
    @Order(1)
    void register_thenCanAccessProtectedEndpoint() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "integ@test.com",
                                "password", "password123",
                                "firstName", "Integ",
                                "lastName", "Test"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        TokenResponse response = objectMapper.readValue(body, TokenResponse.class);
        accessToken = response.accessToken();

        assertThat(accessToken).isNotBlank();
    }

    @Test
    @Order(2)
    void createAccount_withValidToken_returns201() throws Exception {
        assertThat(accessToken).as("accessToken must be set by order-1 test").isNotNull();

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "My Cash",
                                "accountType", "CASH",
                                "currency", "USD",
                                "initialBalance", "100.00"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("My Cash"))
                .andExpect(jsonPath("$.currentBalance").value(100.0));
    }

    @Test
    @Order(3)
    void getAccounts_withValidToken_returnsCreatedAccount() throws Exception {
        assertThat(accessToken).isNotNull();

        mockMvc.perform(get("/api/v1/accounts")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("My Cash"));
    }

    @Test
    @Order(4)
    void protectedEndpoint_withNoToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(5)
    void createAccount_writesAuditEntryToPostgres() throws Exception {
        assertThat(accessToken).as("accessToken must be set by order-1 test").isNotNull();

        long beforeCount = auditLogRepository.count();

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Audit Test Account",
                                "accountType", "BANK",
                                "currency", "EUR",
                                "initialBalance", "0.00"
                        ))))
                .andExpect(status().isCreated());

        // Short wait for the REQUIRES_NEW audit transaction (afterCompletion) to commit
        Thread.sleep(200);
        assertThat(auditLogRepository.count()).isGreaterThan(beforeCount);
    }

    @Test
    @Order(6)
    void activityEndpoint_returnsEventsForCurrentUser() throws Exception {
        assertThat(accessToken).isNotNull();

        mockMvc.perform(get("/api/v1/activity")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].action").isNotEmpty());
    }
}
