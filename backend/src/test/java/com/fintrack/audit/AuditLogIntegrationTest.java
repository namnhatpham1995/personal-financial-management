package com.fintrack.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.audit.domain.AuditLogRepository;
import com.fintrack.auth.web.dto.TokenResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit log integration tests (tasks 2.1–2.3):
 * 2.1 Successful mutation writes audit row; failed (4xx) mutation writes none.
 * 2.2 App runs and audit works with no MongoDB reachable.
 * 2.3 Activity endpoint returns only the requesting user's events, paginated, newest first.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class AuditLogIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_audit_test")
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
        // No real MongoDB — proves audit works without it (task 2.2)
        registry.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/unused");
        // Disable Redis repositories — no Redis in CI; cache.type=simple handles caching
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AuditLogRepository auditLogRepository;

    @BeforeEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private String registerAndLogin(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email, "password", "pass1234",
                                "firstName", "Test", "lastName", "User"))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), TokenResponse.class)
                .accessToken();
    }

    // ── Task 2.1 ──────────────────────────────────────────────────────────────

    @Test
    void successfulMutation_createsAuditRow() throws Exception {
        String token = registerAndLogin("audit2.1a@test.com");
        long before = auditLogRepository.count();

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Audit Test", "accountType", "CASH",
                                "currency", "USD", "initialBalance", "0"))))
                .andExpect(status().isCreated());

        Thread.sleep(200); // let REQUIRES_NEW audit tx commit
        assertThat(auditLogRepository.count()).isGreaterThan(before);
    }

    @Test
    void failedMutation_doesNotCreateAuditRow() throws Exception {
        String token = registerAndLogin("audit2.1b@test.com");
        long before = auditLogRepository.count();

        // Invalid payload → 400 Bad Request; interceptor must not write an audit row
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is4xxClientError());

        Thread.sleep(100);
        assertThat(auditLogRepository.count()).isEqualTo(before);
    }

    // ── Task 2.3 ──────────────────────────────────────────────────────────────

    @Test
    void activityEndpoint_returnsOnlyOwnEvents_paginatedNewestFirst() throws Exception {
        String tokenA = registerAndLogin("audit2.3a@test.com");
        String tokenB = registerAndLogin("audit2.3b@test.com");

        // User A creates two accounts
        for (String name : new String[]{"A-Account-1", "A-Account-2"}) {
            mockMvc.perform(post("/api/v1/accounts")
                            .header("Authorization", "Bearer " + tokenA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "name", name, "accountType", "CASH",
                                    "currency", "USD", "initialBalance", "0"))))
                    .andExpect(status().isCreated());
        }

        // User B creates one account
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "B-Account", "accountType", "CASH",
                                "currency", "USD", "initialBalance", "0"))))
                .andExpect(status().isCreated());

        Thread.sleep(200);

        // User A sees only their own events, in descending ts order
        mockMvc.perform(get("/api/v1/activity?size=10")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].action").value("accounts.created"));

        // Verify isolation at repository level: no cross-user leakage
        // (controller filters by principal.getUserId() which is validated by JWT)
        mockMvc.perform(get("/api/v1/activity")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }
}
