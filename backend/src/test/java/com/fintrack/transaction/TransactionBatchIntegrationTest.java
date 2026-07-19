package com.fintrack.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.auth.domain.User;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.idempotency.domain.IdempotencyOperation;
import com.fintrack.idempotency.domain.IdempotencyOperationState;
import com.fintrack.idempotency.repository.IdempotencyOperationRepository;
import com.fintrack.idempotency.service.IdempotencyHasher;
import com.fintrack.idempotency.service.IdempotentBatchRowExecutor;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers tasks.md 3.4: partial success, interruption/resume, concurrent same-row submission, same
 * clientRequestId across users, changed-payload conflict, and exact-once transfer balance effects
 * for {@code POST /transactions/batch} under the required batch {@code Idempotency-Key} + per-row
 * {@code clientRequestId} contract (see openspec/changes/harden-idempotent-mutations/specs/
 * transaction-management/spec.md "Requirement: Idempotent batch transaction creation").
 */
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
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        r.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/unused");
        r.add("spring.data.redis.repositories.enabled", () -> "false");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired IdempotencyOperationRepository idempotencyOperationRepository;
    @Autowired IdempotencyHasher idempotencyHasher;
    @Autowired UserRepository userRepository;

    // ── Missing header ─────────────────────────────────────────────────────

    @Test
    void batch_missingIdempotencyKey_returns400() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "batch.nokey@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "EUR");
        Map<String, Object> row = rowBody(clientRequestId(), "EXPENSE", "10.00", accountId, null);

        mockMvc.perform(post("/api/v1/transactions/batch")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("transactions", List.of(row)))))
                .andExpect(status().isBadRequest());
    }

    // ── Partial success ─────────────────────────────────────────────────────

    @Test
    void batch_createsValidRowsAndReportsInvalidRows() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "batch.partial@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "EUR");
        Map<String, Object> valid = rowBody(clientRequestId(), "EXPENSE", "10.00", accountId, null);
        Map<String, Object> invalid = rowBody(clientRequestId(), "EXPENSE", "5.00", "999999", null);

        JsonNode result = batch(jwt, UUID.randomUUID().toString(), List.of(valid, invalid));

        assertThat(result.get("results").get(0).get("status").asText()).isEqualTo("CREATED");
        assertThat(result.get("results").get(1).get("status").asText()).isEqualTo("FAILED");
    }

    // ── Row replay (same clientRequestId, same payload) ─────────────────────

    @Test
    void batch_sameClientRequestIdSamePayload_replaysWithoutNewBalanceEffect() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "batch.replay@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "EUR");
        String rowId = clientRequestId();
        Map<String, Object> row = rowBody(rowId, "EXPENSE", "20.00", accountId, null);

        JsonNode first = batch(jwt, UUID.randomUUID().toString(), List.of(row));
        assertThat(first.get("results").get(0).get("status").asText()).isEqualTo("CREATED");
        String firstTxId = first.get("results").get(0).get("transaction").get("id").asText();

        JsonNode second = batch(jwt, UUID.randomUUID().toString(), List.of(row));
        assertThat(second.get("results").get(0).get("status").asText()).isEqualTo("REPLAYED");
        assertThat(second.get("results").get(0).get("transaction").get("id").asText()).isEqualTo(firstTxId);

        assertThat(transactionCount(jwt)).isEqualTo(1);
    }

    // ── Row conflict (same clientRequestId, different payload) ──────────────

    @Test
    void batch_sameClientRequestIdDifferentPayload_returnsConflictAndLeavesOriginalUnchanged() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "batch.conflict@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "EUR");
        String rowId = clientRequestId();
        Map<String, Object> row = rowBody(rowId, "EXPENSE", "30.00", accountId, null);
        Map<String, Object> changedRow = rowBody(rowId, "EXPENSE", "31.00", accountId, null);

        JsonNode first = batch(jwt, UUID.randomUUID().toString(), List.of(row));
        assertThat(first.get("results").get(0).get("status").asText()).isEqualTo("CREATED");
        String firstTxId = first.get("results").get(0).get("transaction").get("id").asText();

        JsonNode second = batch(jwt, UUID.randomUUID().toString(), List.of(changedRow));
        assertThat(second.get("results").get(0).get("status").asText()).isEqualTo("CONFLICT");

        assertThat(transactionCount(jwt)).isEqualTo(1);
        // Original transaction untouched.
        MvcResult getResult = mockMvc.perform(get("/api/v1/transactions/" + firstTxId)
                        .header("Authorization", "Bearer " + jwt))
                .andReturn();
        java.math.BigDecimal storedAmount = new java.math.BigDecimal(
                objectMapper.readTree(getResult.getResponse().getContentAsString()).get("amount").asText());
        assertThat(storedAmount).isEqualByComparingTo("30.00");
    }

    // ── Same clientRequestId literal across two different users ─────────────

    @Test
    void batch_sameClientRequestIdAcrossDifferentUsers_bothSucceedIndependently() throws Exception {
        String jwtA = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "batch.userA@test.com");
        String jwtB = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "batch.userB@test.com");
        String accountA = HttpTestHelper.createAccount(mockMvc, objectMapper, jwtA, "EUR");
        String accountB = HttpTestHelper.createAccount(mockMvc, objectMapper, jwtB, "EUR");
        String sharedRowId = clientRequestId();

        JsonNode resultA = batch(jwtA, UUID.randomUUID().toString(),
                List.of(rowBody(sharedRowId, "EXPENSE", "12.00", accountA, null)));
        JsonNode resultB = batch(jwtB, UUID.randomUUID().toString(),
                List.of(rowBody(sharedRowId, "EXPENSE", "12.00", accountB, null)));

        assertThat(resultA.get("results").get(0).get("status").asText()).isEqualTo("CREATED");
        assertThat(resultB.get("results").get(0).get("status").asText()).isEqualTo("CREATED");
        assertThat(transactionCount(jwtA)).isEqualTo(1);
        assertThat(transactionCount(jwtB)).isEqualTo(1);
    }

    // ── Concurrent same-row submission ───────────────────────────────────────

    @Test
    void batch_concurrentSameRowSubmission_createsExactlyOneTransaction() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "batch.race@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "EUR");
        String rowId = clientRequestId();
        List<Map<String, Object>> rows = List.of(rowBody(rowId, "EXPENSE", "40.00", accountId, null));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);

            List<Future<MvcResult>> futures = List.of(
                    executor.submit(() -> raceBatch(jwt, UUID.randomUUID().toString(), rows, ready, go)),
                    executor.submit(() -> raceBatch(jwt, UUID.randomUUID().toString(), rows, ready, go)));

            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get(15, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).isEqualTo(201);
            }
        } finally {
            executor.shutdown();
        }

        assertThat(transactionCount(jwt)).isEqualTo(1);
    }

    private MvcResult raceBatch(String jwt, String batchKey, List<Map<String, Object>> rows,
                                 CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown();
        go.await();
        return mockMvc.perform(post("/api/v1/transactions/batch")
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", batchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("transactions", rows))))
                .andReturn();
    }

    // ── Interruption/resume ──────────────────────────────────────────────────

    /**
     * Simulates a batch process that died after row 1's independent {@code REQUIRES_NEW} claim
     * committed but before the batch finished: pre-completes row 1's claim directly via the
     * repository (row claims are keyed only by {@code (user, "transaction.batch.row", key_hash)}
     * — independent of any particular batch envelope), then submits the full original batch.
     * Row 1 must come back REPLAYED with no new balance effect; row 2 must be created fresh.
     */
    @Test
    void batch_interruptedBatchResumes_replaysCompletedRowsAndCreatesRemaining() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "batch.resume@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "EUR");
        User user = userRepository.findByEmail("batch.resume@test.com").orElseThrow();

        String rowId1 = clientRequestId();
        String rowId2 = clientRequestId();
        Map<String, Object> row1Body = rowBody(rowId1, "EXPENSE", "50.00", accountId, null);
        Map<String, Object> row2Body = rowBody(rowId2, "EXPENSE", "15.00", accountId, null);

        // Real prior commit for row 1, standing in for "row 1 already committed before the crash".
        String priorTxJson = createSingleTransactionRawJson(jwt, "EXPENSE", "50.00", accountId, null);
        JsonNode priorTx = objectMapper.readTree(priorTxJson);
        String priorTxId = priorTx.get("id").asText();

        // Fabricate row 1's completed claim to match what the row executor would have written.
        Object row1RequestPayload = extractCreateTransactionRequestPayload(row1Body);
        String keyHash = idempotencyHasher.hashKey(rowId1);
        String requestHash = idempotencyHasher.hashJsonRequest(IdempotentBatchRowExecutor.ROW_OPERATION, row1RequestPayload);
        idempotencyOperationRepository.save(IdempotencyOperation.builder()
                .user(user)
                .operation(IdempotentBatchRowExecutor.ROW_OPERATION)
                .keyHash(keyHash)
                .requestHash(requestHash)
                .state(IdempotencyOperationState.COMPLETED)
                .responseStatus(201)
                .responseContentType(MediaType.APPLICATION_JSON_VALUE)
                .responseBody(priorTxJson)
                .completedAt(Instant.now())
                .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                .build());

        // Resume: submit the full original batch (both rows) under one batch key.
        JsonNode resumed = batch(jwt, UUID.randomUUID().toString(), List.of(row1Body, row2Body));

        assertThat(resumed.get("results").get(0).get("status").asText()).isEqualTo("REPLAYED");
        assertThat(resumed.get("results").get(0).get("transaction").get("id").asText()).isEqualTo(priorTxId);
        assertThat(resumed.get("results").get(1).get("status").asText()).isEqualTo("CREATED");

        // Exactly two transactions total: the pre-existing row-1 commit plus the fresh row 2.
        assertThat(transactionCount(jwt)).isEqualTo(2);
    }

    // ── Exact-once TRANSFER balance effect under replay ──────────────────────

    @Test
    void batch_transferRowReplayedMultipleTimes_exactOnceBalanceEffect() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "batch.transfer@test.com");
        String sourceAccountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "EUR");
        String destAccountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "EUR");
        String rowId = clientRequestId();
        Map<String, Object> transferRow = rowBody(rowId, "TRANSFER", "60.00", sourceAccountId, destAccountId);

        batch(jwt, UUID.randomUUID().toString(), List.of(transferRow));
        batch(jwt, UUID.randomUUID().toString(), List.of(transferRow));
        JsonNode third = batch(jwt, UUID.randomUUID().toString(), List.of(transferRow));
        assertThat(third.get("results").get(0).get("status").asText()).isEqualTo("REPLAYED");

        assertThat(new java.math.BigDecimal(accountBalance(jwt, sourceAccountId))).isEqualByComparingTo("-60.00");
        assertThat(new java.math.BigDecimal(accountBalance(jwt, destAccountId))).isEqualByComparingTo("60.00");
        assertThat(transactionCount(jwt)).isEqualTo(1);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String clientRequestId() {
        return UUID.randomUUID().toString().replace("-", "") + "ab";
    }

    private Map<String, Object> rowBody(String clientRequestId, String type, String amount,
                                         String accountId, String transferAccountId) {
        java.util.HashMap<String, Object> txMap = new java.util.HashMap<>();
        txMap.put("transactionType", type);
        txMap.put("amount", amount);
        txMap.put("transactionDate", "2026-01-01");
        txMap.put("accountId", accountId);
        if (transferAccountId != null) {
            txMap.put("transferAccountId", transferAccountId);
        }
        return Map.of("clientRequestId", clientRequestId, "transaction", txMap);
    }

    /**
     * Reconstructs the exact object shape {@code TransactionService.createBatch} hashes for a row
     * (its {@code CreateTransactionRequest}), from the same map used to build the HTTP row body,
     * so a directly-fabricated claim's request hash matches what the real code path would compute.
     */
    @SuppressWarnings("unchecked")
    private Object extractCreateTransactionRequestPayload(Map<String, Object> rowBody) throws Exception {
        Map<String, Object> tx = (Map<String, Object>) rowBody.get("transaction");
        return objectMapper.readValue(objectMapper.writeValueAsString(Map.of(
                        "transactionType", tx.get("transactionType"),
                        "amount", tx.get("amount"),
                        "transactionDate", tx.get("transactionDate"),
                        "accountId", Long.parseLong(tx.get("accountId").toString())
                )),
                com.fintrack.transaction.web.dto.CreateTransactionRequest.class);
    }

    private String createSingleTransactionRawJson(String jwt, String type, String amount,
                                                    String accountId, String transferAccountId) throws Exception {
        java.util.HashMap<String, Object> body = new java.util.HashMap<>();
        body.put("transactionType", type);
        body.put("amount", amount);
        body.put("transactionDate", "2026-01-01");
        body.put("accountId", accountId);
        if (transferAccountId != null) {
            body.put("transferAccountId", transferAccountId);
        }
        MvcResult result = mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private JsonNode batch(String token, String idempotencyKey, List<Map<String, Object>> rows) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/transactions/batch")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("transactions", rows))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int transactionCount(String jwt) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/transactions").header("Authorization", "Bearer " + jwt))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("totalElements").asInt();
    }

    private String accountBalance(String jwt, String accountId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/accounts/" + accountId)
                        .header("Authorization", "Bearer " + jwt))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("currentBalance").asText();
    }
}
