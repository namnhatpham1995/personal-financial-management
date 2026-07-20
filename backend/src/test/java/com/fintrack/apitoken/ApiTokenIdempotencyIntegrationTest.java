package com.fintrack.apitoken;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.apitoken.domain.ApiToken;
import com.fintrack.apitoken.repository.ApiTokenRepository;
import com.fintrack.audit.domain.AuditLog;
import com.fintrack.audit.domain.AuditLogRepository;
import com.fintrack.auth.web.dto.TokenResponse;
import com.fintrack.support.HttpTestHelper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Covers tasks.md 5.4-5.6: PAT-creation idempotency binding. Because the plaintext token is
 * never persisted, a same-key retry can never replay the original response body — it always
 * gets a typed 409 with the existing token's non-secret metadata instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class ApiTokenIdempotencyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_pat_idem_test")
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
        registry.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/fintrack_pat_idem_test_unused");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
        registry.add("app.rate-limit.auth-requests-per-minute", () -> "1000");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ApiTokenRepository apiTokenRepository;
    @Autowired AuditLogRepository auditLogRepository;

    // ── Lost-response retry ─────────────────────────────────────────────────

    @Test
    void lostResponseRetry_returns409WithOriginalMetadata_noSecondToken_noPlaintextOnRetry() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "pat.idem.lost@test.com");
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("name", "Claude Desktop", "scope", "READ", "expiryDays", 90);

        MvcResult first = createPat(jwt, key, body);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        JsonNode firstNode = objectMapper.readTree(first.getResponse().getContentAsString());
        long originalId = firstNode.get("token").get("id").asLong();
        String originalPlaintext = firstNode.get("plaintextToken").asText();

        MvcResult retry = createPat(jwt, key, body);
        assertThat(retry.getResponse().getStatus()).isEqualTo(409);
        String retryBody = retry.getResponse().getContentAsString();
        JsonNode retryNode = objectMapper.readTree(retryBody);
        assertThat(retryNode.get("error").asText()).isEqualTo("api_token_idempotency_conflict");
        assertThat(retryNode.get("existingToken").get("id").asLong()).isEqualTo(originalId);
        assertThat(retryBody).doesNotContain(originalPlaintext);
        assertThat(retryBody).doesNotContain("plaintextToken");

        assertThat(apiTokenRepository.findAllByUserIdOrderByCreatedAtDesc(userIdFor(jwt))).hasSize(1);
    }

    // ── Concurrent same-key requests ────────────────────────────────────────

    @Test
    void concurrentSameKeyRequests_exactlyOneTokenRow_atMostOnePlaintextResponse() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "pat.idem.race@test.com");
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("name", "Race Token", "scope", "WRITE", "expiryDays", 30);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = List.of(
                    executor.submit(() -> raceCreate(jwt, key, body, ready, go)),
                    executor.submit(() -> raceCreate(jwt, key, body, ready, go)));

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            int createdCount = 0;
            int conflictCount = 0;
            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get(15, TimeUnit.SECONDS);
                int status = result.getResponse().getStatus();
                if (status == 201) {
                    createdCount++;
                } else if (status == 409) {
                    conflictCount++;
                } else {
                    throw new AssertionError("Unexpected status " + status);
                }
            }

            assertThat(createdCount).isEqualTo(1);
            assertThat(conflictCount).isEqualTo(1);
        } finally {
            executor.shutdown();
        }

        assertThat(apiTokenRepository.findAllByUserIdOrderByCreatedAtDesc(userIdFor(jwt))).hasSize(1);
    }

    private MvcResult raceCreate(String jwt, String key, Map<String, Object> body,
                                  CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown();
        go.await();
        return createPat(jwt, key, body);
    }

    // ── Same key, different settings ────────────────────────────────────────

    @Test
    void sameKeyDifferentSettings_returns409_noSecondToken() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "pat.idem.changed@test.com");
        String key = UUID.randomUUID().toString();

        MvcResult first = createPat(jwt, key, Map.of("name", "Original", "scope", "READ", "expiryDays", 30));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        MvcResult second = createPat(jwt, key, Map.of("name", "Different Name", "scope", "WRITE", "expiryDays", 365));
        assertThat(second.getResponse().getStatus()).isEqualTo(409);

        assertThat(apiTokenRepository.findAllByUserIdOrderByCreatedAtDesc(userIdFor(jwt))).hasSize(1);
    }

    // ── Same literal key, two different users ───────────────────────────────

    @Test
    void sameKeyDifferentUsers_bothSucceedIndependently() throws Exception {
        String jwtA = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "pat.idem.userA@test.com");
        String jwtB = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "pat.idem.userB@test.com");
        String sharedKey = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("name", "Shared Key Token", "scope", "READ", "expiryDays", 30);

        MvcResult resultA = createPat(jwtA, sharedKey, body);
        MvcResult resultB = createPat(jwtB, sharedKey, body);

        assertThat(resultA.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultB.getResponse().getStatus()).isEqualTo(201);
        assertThat(apiTokenRepository.findAllByUserIdOrderByCreatedAtDesc(userIdFor(jwtA))).hasSize(1);
        assertThat(apiTokenRepository.findAllByUserIdOrderByCreatedAtDesc(userIdFor(jwtB))).hasSize(1);
    }

    // ── Keyless create still returns plaintext exactly once ────────────────

    @Test
    void keylessCreate_stillReturnsPlaintextOnce() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "pat.idem.nokey@test.com");
        Map<String, Object> body = Map.of("name", "No Key Token", "scope", "READ", "expiryDays", 30);

        MvcResult result = mockMvc.perform(post("/api/v1/tokens")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(node.get("plaintextToken").asText()).startsWith("fintrack_pat_");
    }

    // ── No plaintext anywhere durable: table, idempotency hash columns, audit log ──────────

    @Test
    void noPlaintextPersisted_inTokenTable_hashColumns_orAuditLog() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "pat.idem.noplaintext@test.com");
        String key = UUID.randomUUID().toString();

        MvcResult created = createPat(jwt, key, Map.of("name", "Audit Check", "scope", "WRITE", "expiryDays", 30));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String plaintext = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("plaintextToken").asText();

        ApiToken stored = apiTokenRepository.findAllByUserIdOrderByCreatedAtDesc(userIdFor(jwt)).get(0);
        assertThat(stored.getTokenHash()).isNotEqualTo(plaintext);
        assertThat(stored.getTokenHash()).doesNotContain(plaintext);
        assertThat(stored.getIdempotencyKeyHash()).isNotEqualTo(key);
        assertThat(stored.getIdempotencyKeyHash()).doesNotContain(plaintext);
        assertThat(stored.getRequestHash()).doesNotContain(plaintext);

        Thread.sleep(200); // let the REQUIRES_NEW audit write commit
        List<AuditLog> entries = auditLogRepository
                .findByUserIdOrderByTsDesc(userIdFor(jwt), PageRequest.of(0, 20))
                .getContent();
        assertThat(entries).isNotEmpty();
        for (AuditLog entry : entries) {
            String metaJson = objectMapper.writeValueAsString(entry.getMeta());
            assertThat(metaJson).doesNotContain(plaintext);
            assertThat(metaJson).doesNotContain(key);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private MvcResult createPat(String jwt, String idempotencyKey, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/v1/tokens")
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private Long userIdFor(String jwt) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + jwt))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }
}
