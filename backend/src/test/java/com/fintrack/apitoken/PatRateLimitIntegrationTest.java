package com.fintrack.apitoken;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Separate Spring context (own low app.pat.requests-per-minute) so this test's throttling
 * doesn't bleed into PatAuthenticationIntegrationTest's functional assertions, which share
 * the default 60/min limit and would otherwise trip 429 unexpectedly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class PatRateLimitIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_pat_ratelimit_test")
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
        registry.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/unused");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
        registry.add("app.pat.requests-per-minute", () -> "2");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tokenExceedingRateLimit_returns429_withoutAffectingOtherTokens() throws Exception {
        String jwt = registerAndLogin("pat.ratelimit@test.com");
        String patA = createPat(jwt, "READ");
        String patB = createPat(jwt, "READ");

        // Limit is 2/min for token A: first two succeed, third is throttled.
        mockMvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + patA))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + patA))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + patA))
                .andExpect(status().isTooManyRequests());

        // Token B has its own independent bucket.
        mockMvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + patB))
                .andExpect(status().isOk());
    }

    private String registerAndLogin(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email, "password", "pass1234",
                                "firstName", "Test", "lastName", "User"))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("accessToken").asText();
    }

    private String createPat(String jwt, String scope) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/tokens")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Rate Limit Token", "scope", scope, "expiryDays", 30))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("plaintextToken").asText();
    }
}
