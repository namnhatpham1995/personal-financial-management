package com.fintrack.idempotency;

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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Covers tasks.md 2.6: the same literal {@code Idempotency-Key} string used by two different
 * users — one authenticated via JWT, one via a PAT — must not collide. Both requests create their
 * own resource and their own idempotency operation row; neither sees a replay or conflict of the
 * other user's request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class IdempotencyCrossUserIsolationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_idem_isolation_test")
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
        registry.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/fintrack_idem_isolation_test_unused");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
        registry.add("app.rate-limit.auth-requests-per-minute", () -> "1000");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private String createPat(String jwt, String scope) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/tokens")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Idempotency Cross-User Token", "scope", scope, "expiryDays", 30))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("plaintextToken").asText();
    }

    @Test
    void sameLiteralKey_acrossJwtUserAndPatUser_createsTwoIndependentAccounts() throws Exception {
        String jwtA = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idem.isolation.jwt@test.com");
        String jwtB = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idem.isolation.pat@test.com");
        String patB = createPat(jwtB, "WRITE");

        String sharedKey = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of(
                "name", "Cross-User Idem Account", "accountType", "CASH",
                "currency", "USD", "initialBalance", "0.00");

        MvcResult resultA = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + jwtA)
                        .header("Idempotency-Key", sharedKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(resultA.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultA.getResponse().getHeader("Idempotency-Replayed")).isNull();

        MvcResult resultB = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + patB)
                        .header("Idempotency-Key", sharedKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(resultB.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultB.getResponse().getHeader("Idempotency-Replayed")).isNull();

        String idA = objectMapper.readTree(resultA.getResponse().getContentAsString()).get("id").asText();
        String idB = objectMapper.readTree(resultB.getResponse().getContentAsString()).get("id").asText();
        assertThat(idA).isNotEqualTo(idB);

        // Each user's own account list contains exactly their own row — no cross-user leak, no
        // collision on the shared key.
        MvcResult listA = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/accounts").header("Authorization", "Bearer " + jwtA))
                .andReturn();
        assertThat(objectMapper.readTree(listA.getResponse().getContentAsString()).size()).isEqualTo(1);

        MvcResult listB = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/accounts").header("Authorization", "Bearer " + jwtB))
                .andReturn();
        assertThat(objectMapper.readTree(listB.getResponse().getContentAsString()).size()).isEqualTo(1);
    }
}
