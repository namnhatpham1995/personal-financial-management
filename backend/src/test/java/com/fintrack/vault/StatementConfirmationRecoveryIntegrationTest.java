package com.fintrack.vault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.account.domain.Account;
import com.fintrack.account.repository.AccountRepository;
import com.fintrack.support.HttpTestHelper;
import com.fintrack.transaction.domain.Transaction;
import com.fintrack.transaction.repository.TransactionRepository;
import com.fintrack.vault.domain.VaultDocument;
import com.fintrack.vault.repository.VaultDocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers tasks.md 6.9: combined PostgreSQL+Mongo integration tests for statement confirmation
 * resumability, per the "Confirmed rows are normalized into PostgreSQL transactions idempotently"
 * requirement in specs/statement-import/spec.md — failure after row one, concurrent confirmation,
 * response loss after activation, changed selection, exact balance effects, source-document
 * links, and identical legitimate rows via occurrence-ordinal fingerprints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class StatementConfirmationRecoveryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_stmt_confirm_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

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
        registry.add("spring.data.mongodb.uri", () -> mongo.getReplicaSetUrl("fintrack_stmt_confirm_test"));
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired VaultDocumentRepository vaultDocumentRepository;

    private static final String VALID_CSV =
            "Date,Description,Amount\n" +
            "2026-02-01,Salary,500.00\n" +
            "2026-02-02,Groceries,-40.00\n";

    private static final String IDENTICAL_ROWS_CSV =
            "Date,Description,Amount\n" +
            "2026-02-05,Coffee,-5.00\n" +
            "2026-02-05,Coffee,-5.00\n";

    private String upload(String jwt, String accountId, String csv, String key) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "statement.csv", MediaType.TEXT_PLAIN_VALUE,
                csv.getBytes(StandardCharsets.UTF_8));
        MvcResult result = mockMvc.perform(multipart("/api/vault/import/upload")
                        .file(file)
                        .param("accountId", accountId)
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("documentId").asText();
    }

    private List<String> dedupKeys(String jwt, String documentId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/vault/import/" + documentId + "/rows")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> keys = new ArrayList<>();
        rows.forEach(r -> keys.add(r.get("dedupKey").asText()));
        return keys;
    }

    private MvcResult confirm(String jwt, String documentId, List<String> selected, String key) throws Exception {
        return mockMvc.perform(post("/api/vault/import/" + documentId + "/confirm")
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("selectedDedupKeys", selected))))
                .andReturn();
    }

    private BigDecimal expectedBalance(Long accountId) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        BigDecimal net = BigDecimal.ZERO;
        for (Transaction tx : transactionRepository.findConnectedToAccount(accountId)) {
            net = switch (tx.getTransactionType()) {
                case INCOME -> net.add(tx.getAmount());
                case EXPENSE -> net.subtract(tx.getAmount());
                case TRANSFER -> net; // statement rows are never TRANSFER
            };
        }
        return account.getInitialBalance().add(net);
    }

    private void assertBalanceInvariant(Long accountId) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        assertThat(account.getCurrentBalance()).isEqualByComparingTo(expectedBalance(accountId));
    }

    // ── Failure after row one is resumable ──────────────────────────────────

    @Test
    void failureAfterRowOne_isResumableAndDoesNotDuplicateSucceededRow() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "confirm.failafterone@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String documentId = upload(jwt, accountId, VALID_CSV, "upload-key-failafterone-0001");
        List<String> keys = dedupKeys(jwt, documentId);
        String goodKey = keys.get(0);

        // Tamper the staged payload to inject a row that fails bean validation (negative amount)
        // under its own dedup key, so the first selected row succeeds and the second fails.
        VaultDocument doc = vaultDocumentRepository.findById(documentId).orElseThrow();
        List<Map<String, Object>> rows = new ArrayList<>((List<Map<String, Object>>) doc.getPayload().get("rows"));
        String badKey = "badrow-fingerprint-0000000000000000000000000000";
        Map<String, Object> badRow = new HashMap<>();
        badRow.put("date", "2026-02-03");
        badRow.put("amount", "-1.00"); // fails @DecimalMin("0.01") in CreateTransactionRequest
        badRow.put("type", "EXPENSE");
        badRow.put("description", "Bad row");
        badRow.put("dedupKey", badKey);
        rows.add(badRow);
        Map<String, Object> payload = new HashMap<>(doc.getPayload());
        payload.put("rows", rows);
        doc.setPayload(payload);
        vaultDocumentRepository.save(doc);

        List<String> selection = List.of(goodKey, badKey);
        String confirmKey = "confirm-key-failafterone-0000000001";

        MvcResult first = confirm(jwt, documentId, selection, confirmKey);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        assertThat(firstBody.get("created").asInt()).isEqualTo(1);
        assertThat(firstBody.get("failed").asInt()).isEqualTo(1);

        long txCountAfterFirst = transactionRepository.findConnectedToAccount(Long.valueOf(accountId)).size();
        assertThat(txCountAfterFirst).isEqualTo(1);

        // Retry with the same key/selection: the document is already ACTIVE, so this replays the
        // stored result rather than reprocessing — the succeeded row must not be recreated.
        MvcResult retry = confirm(jwt, documentId, selection, confirmKey);
        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
        JsonNode retryBody = objectMapper.readTree(retry.getResponse().getContentAsString());
        assertThat(retryBody.get("created").asInt()).isEqualTo(1);
        assertThat(retryBody.get("failed").asInt()).isEqualTo(1);

        long txCountAfterRetry = transactionRepository.findConnectedToAccount(Long.valueOf(accountId)).size();
        assertThat(txCountAfterRetry).isEqualTo(1);
        assertBalanceInvariant(Long.valueOf(accountId));
    }

    // ── Concurrent confirmation ──────────────────────────────────────────────

    @Test
    void concurrentConfirmation_createsExactlyOneSetOfTransactions() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "confirm.concurrent@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String documentId = upload(jwt, accountId, VALID_CSV, "upload-key-concurrent-0000001");
        List<String> selection = dedupKeys(jwt, documentId);
        String confirmKey = "confirm-key-concurrent-000000000001";

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<MvcResult> task = () -> {
            ready.countDown();
            go.await();
            return confirm(jwt, documentId, selection, confirmKey);
        };
        try {
            Future<MvcResult> f1 = pool.submit(task);
            Future<MvcResult> f2 = pool.submit(task);
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            MvcResult r1 = f1.get(15, TimeUnit.SECONDS);
            MvcResult r2 = f2.get(15, TimeUnit.SECONDS);

            assertThat(r1.getResponse().getStatus()).isEqualTo(200);
            assertThat(r2.getResponse().getStatus()).isEqualTo(200);
            JsonNode b1 = objectMapper.readTree(r1.getResponse().getContentAsString());
            JsonNode b2 = objectMapper.readTree(r2.getResponse().getContentAsString());
            assertThat(b1.get("created").asInt()).isEqualTo(2);
            assertThat(b2.get("created").asInt()).isEqualTo(2);

            long txCount = transactionRepository.findConnectedToAccount(Long.valueOf(accountId)).size();
            assertThat(txCount).isEqualTo(2);
            assertBalanceInvariant(Long.valueOf(accountId));
        } finally {
            pool.shutdownNow();
        }
    }

    // ── Response loss after activation ──────────────────────────────────────

    @Test
    void responseLostAfterActivation_replayCreatesNoNewTransactions() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "confirm.replay@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String documentId = upload(jwt, accountId, VALID_CSV, "upload-key-replay-00000000001");
        List<String> selection = dedupKeys(jwt, documentId);
        String confirmKey = "confirm-key-replay-0000000000000001";

        confirm(jwt, documentId, selection, confirmKey);
        long txCountAfterFirst = transactionRepository.findConnectedToAccount(Long.valueOf(accountId)).size();
        assertThat(txCountAfterFirst).isEqualTo(2);

        // Simulate the response being lost by simply retrying with the same key/selection.
        MvcResult replay = confirm(jwt, documentId, selection, confirmKey);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        JsonNode replayBody = objectMapper.readTree(replay.getResponse().getContentAsString());
        assertThat(replayBody.get("created").asInt()).isEqualTo(2);

        long txCountAfterReplay = transactionRepository.findConnectedToAccount(Long.valueOf(accountId)).size();
        assertThat(txCountAfterReplay).isEqualTo(txCountAfterFirst);
        assertBalanceInvariant(Long.valueOf(accountId));
    }

    // ── Changed selection during retry ───────────────────────────────────────

    @Test
    void changedSelectionDuringRetry_returns409AndPreservesOriginalState() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "confirm.changedsel@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String documentId = upload(jwt, accountId, VALID_CSV, "upload-key-changedsel-0000001");
        List<String> keys = dedupKeys(jwt, documentId);
        String confirmKey = "confirm-key-changedsel-00000000001";

        confirm(jwt, documentId, List.of(keys.get(0)), confirmKey);
        BigDecimal balanceAfterFirst = accountRepository.findById(Long.valueOf(accountId)).orElseThrow().getCurrentBalance();
        long txCountAfterFirst = transactionRepository.findConnectedToAccount(Long.valueOf(accountId)).size();

        MvcResult conflict = confirm(jwt, documentId, keys, confirmKey);
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);

        BigDecimal balanceAfterConflict = accountRepository.findById(Long.valueOf(accountId)).orElseThrow().getCurrentBalance();
        long txCountAfterConflict = transactionRepository.findConnectedToAccount(Long.valueOf(accountId)).size();
        assertThat(balanceAfterConflict).isEqualByComparingTo(balanceAfterFirst);
        assertThat(txCountAfterConflict).isEqualTo(txCountAfterFirst);
    }

    // ── Source-document link ─────────────────────────────────────────────────

    @Test
    void createdTransactions_areLinkedToSourceVaultDocument() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "confirm.sourcelink@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String documentId = upload(jwt, accountId, VALID_CSV, "upload-key-sourcelink-0000001");
        List<String> selection = dedupKeys(jwt, documentId);

        confirm(jwt, documentId, selection, "confirm-key-sourcelink-00000000001");

        List<Transaction> txs = transactionRepository.findConnectedToAccount(Long.valueOf(accountId));
        assertThat(txs).hasSize(2);
        assertThat(txs).allSatisfy(t -> assertThat(t.getSourceDocumentId()).isEqualTo(documentId));
    }

    // ── Identical legitimate rows ────────────────────────────────────────────

    @Test
    void identicalLegitimateRows_getDistinctFingerprintsAndBothImport_reuploadIsStable() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "confirm.identicalrows@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");

        String documentId = upload(jwt, accountId, IDENTICAL_ROWS_CSV, "upload-key-identical-000000001");
        List<String> keys = dedupKeys(jwt, documentId);
        assertThat(keys).hasSize(2);
        assertThat(keys.get(0)).isNotEqualTo(keys.get(1));

        MvcResult confirmResult = confirm(jwt, documentId, keys, "confirm-key-identical-0000000001");
        assertThat(confirmResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(confirmResult.getResponse().getContentAsString());
        assertThat(body.get("created").asInt()).isEqualTo(2);

        long txCount = transactionRepository.findConnectedToAccount(Long.valueOf(accountId)).size();
        assertThat(txCount).isEqualTo(2);
        assertBalanceInvariant(Long.valueOf(accountId));

        // Re-uploading the byte-identical file (new staged document, new upload key) must
        // reproduce the exact same fingerprints in the same order — stable re-import dedup.
        String secondDocumentId = upload(jwt, accountId, IDENTICAL_ROWS_CSV, "upload-key-identical-000000002");
        List<String> secondKeys = dedupKeys(jwt, secondDocumentId);
        assertThat(secondKeys).isEqualTo(keys);

        // Confirming the re-imported rows must be reported as duplicates, not new transactions.
        MvcResult reconfirm = confirm(jwt, secondDocumentId, secondKeys, "confirm-key-identical-0000000002");
        JsonNode reconfirmBody = objectMapper.readTree(reconfirm.getResponse().getContentAsString());
        assertThat(reconfirmBody.get("created").asInt()).isZero();
        assertThat(reconfirmBody.get("duplicate").asInt()).isEqualTo(2);

        long finalTxCount = transactionRepository.findConnectedToAccount(Long.valueOf(accountId)).size();
        assertThat(finalTxCount).isEqualTo(2);
        assertBalanceInvariant(Long.valueOf(accountId));
    }
}
