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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Covers tasks.md 2.1: for each PostgreSQL-backed create endpoint routed through the shared
 * idempotency executor (account, category, budget, transaction, recurring), same-key requests
 * submitted sequentially replay the original resource, same-key requests submitted concurrently
 * still produce exactly one row, and a same-key request with a changed payload is rejected with a
 * 409 conflict that leaves the original resource untouched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class IdempotentCreateReplayIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_idem_replay_test")
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
        registry.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/fintrack_idem_replay_test_unused");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
        registry.add("app.rate-limit.auth-requests-per-minute", () -> "1000");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    // ── Account ─────────────────────────────────────────────────────────────

    @Test
    void account_sequentialReplay_returnsSameResourceAndOnlyOneRow() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idem.acct.seq@test.com");
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = accountBody("Idem Checking");

        assertSequentialReplay(jwt, key, "/api/v1/accounts", body, jwt2 -> accountCount(jwt2));
    }

    @Test
    void account_concurrentRace_createsExactlyOneRow() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idem.acct.race@test.com");
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = accountBody("Idem Race Account");

        assertConcurrentRace(jwt, key, "/api/v1/accounts", body, this::accountCount);
    }

    @Test
    void account_changedPayload_returns409AndLeavesOriginalUnchanged() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idem.acct.conflict@test.com");
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = accountBody("Idem Conflict Account");
        Map<String, Object> differentBody = accountBody("Different Account Name");

        assertConflict(jwt, key, "/api/v1/accounts", body, differentBody, this::accountCount);
    }

    private Map<String, Object> accountBody(String name) {
        return Map.of("name", name, "accountType", "CASH", "currency", "USD", "initialBalance", "10.00");
    }

    private int accountCount(String jwt) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + jwt))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).size();
    }

    // ── Category ────────────────────────────────────────────────────────────

    @Test
    void category_sequentialReplay_returnsSameResourceAndOnlyOneRow() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idem.cat.seq@test.com");
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = categoryBody("Idem Category Seq");

        assertSequentialReplay(jwt, key, "/api/v1/categories", body, jwt2 -> categoryCount(jwt2, "Idem Category Seq"));
    }

    @Test
    void category_concurrentRace_createsExactlyOneRow() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idem.cat.race@test.com");
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = categoryBody("Idem Category Race");

        assertConcurrentRace(jwt, key, "/api/v1/categories", body, jwt2 -> categoryCount(jwt2, "Idem Category Race"));
    }

    @Test
    void category_changedPayload_returns409AndLeavesOriginalUnchanged() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idem.cat.conflict@test.com");
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = categoryBody("Idem Category Conflict");
        Map<String, Object> differentBody = Map.of("name", "Idem Category Conflict", "transactionType", "INCOME");

        assertConflict(jwt, key, "/api/v1/categories", body, differentBody,
                jwt2 -> categoryCount(jwt2, "Idem Category Conflict"));
    }

    private Map<String, Object> categoryBody(String name) {
        return Map.of("name", name, "transactionType", "EXPENSE");
    }

    private int categoryCount(String jwt, String name) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/categories").header("Authorization", "Bearer " + jwt))
                .andReturn();
        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        int count = 0;
        for (JsonNode node : array) {
            if (name.equals(node.get("name").asText())) {
                count++;
            }
        }
        return count;
    }

    // ── Budget ──────────────────────────────────────────────────────────────

    @Test
    void budget_sequentialReplay_returnsSameResourceAndOnlyOneRow() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idem.budget.seq@test.com");
        String categoryId = HttpTestHelper.createCategory(mockMvc, objectMapper, jwt, "Budget Seq Category", "EXPENSE");
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = budgetBody(categoryId, "200.00");

        assertSequentialReplay(jwt, key, "/api/v1/budgets", body, this::budgetCount);
    }

    @Test
    void budget_concurrentRace_createsExactlyOneRow() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idem.budget.race@test.com");
        String categoryId = HttpTestHelper.createCategory(mockMvc, objectMapper, jwt, "Budget Race Category", "EXPENSE");
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = budgetBody(categoryId, "150.00");

        assertConcurrentRace(jwt, key, "/api/v1/budgets", body, this::budgetCount);
    }

    @Test
    void budget_changedPayload_returns409AndLeavesOriginalUnchanged() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idem.budget.conflict@test.com");
        String categoryId = HttpTestHelper.createCategory(mockMvc, objectMapper, jwt, "Budget Conflict Category", "EXPENSE");
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = budgetBody(categoryId, "100.00");
        Map<String, Object> differentBody = budgetBody(categoryId, "999.00");

        assertConflict(jwt, key, "/api/v1/budgets", body, differentBody, this::budgetCount);
    }

    private Map<String, Object> budgetBody(String categoryId, String limit) {
        return Map.of(
                "categoryId", Long.parseLong(categoryId),
                "period", "MONTHLY",
                "amountLimit", limit,
                "startDate", LocalDate.now().withDayOfMonth(1).toString(),
                "currency", "USD");
    }

    private int budgetCount(String jwt) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/budgets").header("Authorization", "Bearer " + jwt))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).size();
    }

    // ── Transaction ─────────────────────────────────────────────────────────

    @Test
    void transaction_sequentialReplay_returnsSameResourceAndOnlyOneRow() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idem.tx.seq@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = transactionBody(accountId, "25.00");

        assertSequentialReplay(jwt, key, "/api/v1/transactions", body, this::transactionCount);
    }

    @Test
    void transaction_concurrentRace_createsExactlyOneRow() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idem.tx.race@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = transactionBody(accountId, "35.00");

        assertConcurrentRace(jwt, key, "/api/v1/transactions", body, this::transactionCount);
    }

    @Test
    void transaction_changedPayload_returns409AndLeavesOriginalUnchanged() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idem.tx.conflict@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = transactionBody(accountId, "45.00");
        Map<String, Object> differentBody = transactionBody(accountId, "46.00");

        assertConflict(jwt, key, "/api/v1/transactions", body, differentBody, this::transactionCount);
    }

    private Map<String, Object> transactionBody(String accountId, String amount) {
        return Map.of(
                "transactionType", "EXPENSE",
                "amount", amount,
                "transactionDate", LocalDate.now().toString(),
                "accountId", Long.parseLong(accountId));
    }

    private int transactionCount(String jwt) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/transactions").header("Authorization", "Bearer " + jwt))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("totalElements").asInt();
    }

    // ── Recurring transaction definition ───────────────────────────────────

    @Test
    void recurring_sequentialReplay_returnsSameResourceAndOnlyOneRow() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idem.rec.seq@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = recurringBody(accountId, "20.00");

        assertSequentialReplay(jwt, key, "/api/v1/recurring-transactions", body, this::recurringCount);
    }

    @Test
    void recurring_concurrentRace_createsExactlyOneRow() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idem.rec.race@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = recurringBody(accountId, "30.00");

        assertConcurrentRace(jwt, key, "/api/v1/recurring-transactions", body, this::recurringCount);
    }

    @Test
    void recurring_changedPayload_returns409AndLeavesOriginalUnchanged() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "idem.rec.conflict@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = recurringBody(accountId, "40.00");
        Map<String, Object> differentBody = recurringBody(accountId, "41.00");

        assertConflict(jwt, key, "/api/v1/recurring-transactions", body, differentBody, this::recurringCount);
    }

    private Map<String, Object> recurringBody(String accountId, String amount) {
        return Map.of(
                "transactionType", "EXPENSE",
                "amount", amount,
                "accountId", Long.parseLong(accountId),
                "frequency", "MONTHLY",
                "intervalValue", 1,
                "startDate", LocalDate.now().toString());
    }

    private int recurringCount(String jwt) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/recurring-transactions").header("Authorization", "Bearer " + jwt))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).size();
    }

    // ── Generic scenario assertions ────────────────────────────────────────

    private void assertSequentialReplay(String jwt, String key, String path, Map<String, Object> body,
                                         RowCounter rowCounter) throws Exception {
        MvcResult first = mockMvc.perform(post(path)
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        String firstId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();
        assertThat(first.getResponse().getHeader("Idempotency-Replayed")).isNull();

        MvcResult second = mockMvc.perform(post(path)
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        String secondId = objectMapper.readTree(second.getResponse().getContentAsString()).get("id").asText();
        assertThat(secondId).isEqualTo(firstId);
        assertThat(second.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("true");

        assertThat(rowCounter.count(jwt)).isEqualTo(1);
    }

    private void assertConcurrentRace(String jwt, String key, String path, Map<String, Object> body,
                                       RowCounter rowCounter) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);

            List<Future<MvcResult>> futures = List.of(
                    executor.submit(() -> raceRequest(jwt, key, path, body, ready, go)),
                    executor.submit(() -> raceRequest(jwt, key, path, body, ready, go)));

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get(15, TimeUnit.SECONDS);
                int status = result.getResponse().getStatus();
                // Winner: 201 (claimed) or 200 (already replayed by the time it read state).
                // Loser: 201/200 replay, or a bounded 409 operation_in_progress retry response.
                assertThat(status).isIn(200, 201, 409);
            }
        } finally {
            executor.shutdown();
        }

        assertThat(rowCounter.count(jwt)).isEqualTo(1);
    }

    private MvcResult raceRequest(String jwt, String key, String path, Map<String, Object> body,
                                   CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown();
        go.await();
        return mockMvc.perform(post(path)
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private void assertConflict(String jwt, String key, String path, Map<String, Object> body,
                                 Map<String, Object> differentBody, RowCounter rowCounter) throws Exception {
        MvcResult first = mockMvc.perform(post(path)
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        MvcResult second = mockMvc.perform(post(path)
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(differentBody)))
                .andReturn();
        assertThat(second.getResponse().getStatus()).isEqualTo(409);

        assertThat(rowCounter.count(jwt)).isEqualTo(1);
    }

    @FunctionalInterface
    private interface RowCounter {
        int count(String jwt) throws Exception;
    }
}
