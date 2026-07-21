package com.fintrack.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.support.HttpTestHelper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers tasks.md 2.2/9.5's enforcement coverage requirement for
 * {@code IdempotencyEnforcementGuard}: when {@code app.idempotency.mode} is set to {@code ENFORCE},
 * a protected create endpoint called without an {@code Idempotency-Key} header returns the typed
 * 400 instead of falling back to unprotected direct execution. The property defaults to
 * {@code OBSERVE} everywhere else, so this is the only test that exercises enforcement.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "app.idempotency.mode=ENFORCE")
class IdempotencyEnforcementIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_idem_enforce_test")
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
        registry.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/fintrack_idem_enforce_test_unused");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
        registry.add("app.rate-limit.auth-requests-per-minute", () -> "1000");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void accountCreate_withoutIdempotencyKey_returns400WhenEnforcementEnabled() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idem.enforce@test.com");

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Should Not Be Created", "accountType", "CASH",
                                "currency", "USD", "initialBalance", "0.00"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing_idempotency_key"));
    }

    @Test
    void accountCreate_withIdempotencyKey_stillSucceedsWhenEnforcementEnabled() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idem.enforce.ok@test.com");

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", "enforcement-test-key-0123456789")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Enforced Account", "accountType", "CASH",
                                "currency", "USD", "initialBalance", "0.00"))))
                .andExpect(status().isCreated());
    }
}
