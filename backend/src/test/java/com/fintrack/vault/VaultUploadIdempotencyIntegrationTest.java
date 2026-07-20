package com.fintrack.vault;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.support.HttpTestHelper;
import com.fintrack.vault.domain.VaultOperation;
import com.fintrack.vault.domain.VaultOperationState;
import com.fintrack.vault.repository.VaultOperationRepository;
import com.fintrack.vault.service.GridFsFileStore;
import com.fintrack.vault.service.VaultOperationRecoveryScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testcontainers coverage (task 6.4) for the Mongo/GridFS upload idempotency coordinator wired
 * into both {@code POST /api/vault/upload} and {@code POST /api/vault/import/upload}: sequential
 * and concurrent replay, changed-file conflict, cross-user isolation, document-save-failure
 * compensation, and stale-operation recovery. Follows the same Postgres+Mongo Testcontainers
 * pattern as {@code StatementImportPipelineIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class VaultUploadIdempotencyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_vault_idem_test")
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
        registry.add("spring.data.mongodb.uri", () -> mongo.getReplicaSetUrl("fintrack_vault_idem_test"));
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired GridFsTemplate gridFsTemplate;
    @Autowired VaultOperationRepository vaultOperationRepository;
    @Autowired GridFsFileStore gridFsFileStore;
    @Autowired VaultOperationRecoveryScheduler recoveryScheduler;
    @Autowired UserRepository userRepository;

    private String register(String email) throws Exception {
        return HttpTestHelper.registerAndLogin(mockMvc, objectMapper, email);
    }

    private Long userIdOf(String email) {
        return userRepository.findByEmail(email).orElseThrow().getId();
    }

    private long countGridFsFilesForUser(Long userId) {
        return gridFsTemplate.find(Query.query(Criteria.where("metadata.userId").is(userId)))
                .into(new ArrayList<>())
                .size();
    }

    // ── sequential replay ───────────────────────────────────────────────────────

    @Test
    void vaultUpload_sequentialReplay_sameKeySameFile_returnsSameDocumentAndOneBinary() throws Exception {
        String email = "vault.replay@test.com";
        String jwt = register(email);
        String key = UUID.randomUUID().toString();
        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.jpg", MediaType.IMAGE_JPEG_VALUE, "receipt-bytes".getBytes(StandardCharsets.UTF_8));

        MvcResult first = mockMvc.perform(multipart("/api/vault/upload")
                        .file(file)
                        .param("type", "RECEIPT")
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andReturn();
        String firstId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();
        assertThat(first.getResponse().getHeader("Idempotency-Replayed")).isNull();

        MvcResult second = mockMvc.perform(multipart("/api/vault/upload")
                        .file(file)
                        .param("type", "RECEIPT")
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn();
        String secondId = objectMapper.readTree(second.getResponse().getContentAsString()).get("id").asText();

        assertThat(secondId).isEqualTo(firstId);
        assertThat(countGridFsFilesForUser(userIdOf(email))).isEqualTo(1);
    }

    @Test
    void statementUpload_sequentialReplay_sameKeySameFile_returnsSameDocumentId() throws Exception {
        String email = "statement.replay@test.com";
        String jwt = register(email);
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String key = UUID.randomUUID().toString();
        String csv = "Date,Description,Amount\n2026-01-05,Salary,1000.00\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "statement.csv", MediaType.TEXT_PLAIN_VALUE, csv.getBytes(StandardCharsets.UTF_8));

        MvcResult first = mockMvc.perform(multipart("/api/vault/import/upload")
                        .file(file)
                        .param("accountId", accountId)
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andReturn();
        String firstDocId = objectMapper.readTree(first.getResponse().getContentAsString()).get("documentId").asText();

        MvcResult second = mockMvc.perform(multipart("/api/vault/import/upload")
                        .file(file)
                        .param("accountId", accountId)
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn();
        String secondDocId = objectMapper.readTree(second.getResponse().getContentAsString()).get("documentId").asText();

        assertThat(secondDocId).isEqualTo(firstDocId);
        assertThat(countGridFsFilesForUser(userIdOf(email))).isEqualTo(1);
    }

    // ── concurrent replay ───────────────────────────────────────────────────────

    @Test
    void vaultUpload_concurrentRetries_onlyOneBinaryAndOneDocumentCreated() throws Exception {
        String email = "vault.concurrent@test.com";
        String jwt = register(email);
        String key = UUID.randomUUID().toString();
        byte[] bytes = "concurrent-receipt-bytes".getBytes(StandardCharsets.UTF_8);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    startLatch.await();
                    MockMultipartFile file = new MockMultipartFile(
                            "file", "receipt.jpg", MediaType.IMAGE_JPEG_VALUE, bytes);
                    return mockMvc.perform(multipart("/api/vault/upload")
                                    .file(file)
                                    .param("type", "RECEIPT")
                                    .header("Authorization", "Bearer " + jwt)
                                    .header("Idempotency-Key", key))
                            .andReturn();
                }));
            }
            startLatch.countDown();

            List<String> documentIds = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get(15, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).isEqualTo(201);
                documentIds.add(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
            }

            assertThat(documentIds.get(0)).isEqualTo(documentIds.get(1));
            assertThat(countGridFsFilesForUser(userIdOf(email))).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ── changed-file conflict ───────────────────────────────────────────────────

    @Test
    void vaultUpload_sameKeyDifferentFile_returnsConflictAndNoSecondArtifact() throws Exception {
        String email = "vault.conflict@test.com";
        String jwt = register(email);
        String key = UUID.randomUUID().toString();
        MockMultipartFile fileA = new MockMultipartFile(
                "file", "receipt-a.jpg", MediaType.IMAGE_JPEG_VALUE, "bytes-a".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile fileB = new MockMultipartFile(
                "file", "receipt-b.jpg", MediaType.IMAGE_JPEG_VALUE, "bytes-b-different".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/vault/upload")
                        .file(fileA)
                        .param("type", "RECEIPT")
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated());

        mockMvc.perform(multipart("/api/vault/upload")
                        .file(fileB)
                        .param("type", "RECEIPT")
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", key))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("idempotency_key_conflict"));

        // Only the first file's binary exists — the conflicting second file was never stored.
        assertThat(countGridFsFilesForUser(userIdOf(email))).isEqualTo(1);
    }

    // ── cross-user isolation ────────────────────────────────────────────────────

    @Test
    void vaultUpload_sameLiteralKeyAcrossUsers_bothSucceedIndependently() throws Exception {
        String emailA = "vault.userA@test.com";
        String emailB = "vault.userB@test.com";
        String jwtA = register(emailA);
        String jwtB = register(emailB);
        String sharedKey = "shared-literal-key-0123456789ab";

        MockMultipartFile fileA = new MockMultipartFile(
                "file", "a.jpg", MediaType.IMAGE_JPEG_VALUE, "user-a-bytes".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile fileB = new MockMultipartFile(
                "file", "b.jpg", MediaType.IMAGE_JPEG_VALUE, "user-b-bytes".getBytes(StandardCharsets.UTF_8));

        MvcResult resultA = mockMvc.perform(multipart("/api/vault/upload")
                        .file(fileA)
                        .param("type", "RECEIPT")
                        .header("Authorization", "Bearer " + jwtA)
                        .header("Idempotency-Key", sharedKey))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult resultB = mockMvc.perform(multipart("/api/vault/upload")
                        .file(fileB)
                        .param("type", "RECEIPT")
                        .header("Authorization", "Bearer " + jwtB)
                        .header("Idempotency-Key", sharedKey))
                .andExpect(status().isCreated())
                .andReturn();

        String idA = objectMapper.readTree(resultA.getResponse().getContentAsString()).get("id").asText();
        String idB = objectMapper.readTree(resultB.getResponse().getContentAsString()).get("id").asText();
        assertThat(idA).isNotEqualTo(idB);
        assertThat(countGridFsFilesForUser(userIdOf(emailA))).isEqualTo(1);
        assertThat(countGridFsFilesForUser(userIdOf(emailB))).isEqualTo(1);
    }

    // ── document-save failure compensation ─────────────────────────────────────

    @Test
    void statementUpload_documentTooLargeToSave_compensatesOrphanedGridFsBinary() throws Exception {
        String email = "statement.compensate@test.com";
        String jwt = register(email);
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String key = UUID.randomUUID().toString();

        // A single BSON document is capped at 16MB. Build a CSV whose parsed "rows" payload alone
        // exceeds that once embedded in the VaultDocument, forcing a real Mongo-side document-save
        // failure *after* the (chunked, unbounded-size) GridFS binary has already been stored.
        StringBuilder csv = new StringBuilder("Date,Description,Amount\n");
        String filler = "X".repeat(1_000_000); // 1MB of a single field, no commas/newlines
        for (int i = 0; i < 20; i++) {
            csv.append("2026-01-0").append((i % 9) + 1).append(",").append(filler).append(",1.00\n");
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "huge-statement.csv", MediaType.TEXT_PLAIN_VALUE,
                csv.toString().getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/vault/import/upload")
                        .file(file)
                        .param("accountId", accountId)
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", key))
                .andExpect(status().is5xxServerError());

        // Compensation must have deleted the orphaned GridFS binary — none survive for this user.
        assertThat(countGridFsFilesForUser(userIdOf(email))).isEqualTo(0);

        Long userId = userIdOf(email);
        VaultOperation op = vaultOperationRepository.findAll().stream()
                .filter(o -> "statement.upload".equals(o.getOperation()) && userId.equals(o.getUserId()))
                .findFirst()
                .orElseThrow();
        assertThat(op.getState()).isEqualTo(VaultOperationState.FAILED);
        // The operation row retains the id of the binary it compensated, for auditability —
        // the binary itself is gone (asserted above), not the reference to it.
        assertThat(op.getGridFsFileId()).isNotNull();
    }

    // ── stale-operation recovery ────────────────────────────────────────────────

    @Test
    void staleProcessingOperation_recoveryJobDeletesOrphanedBinaryAndUnsticksOperation() throws Exception {
        String email = "vault.stale@test.com";
        register(email);
        Long userId = userIdOf(email);

        // Simulate a process that claimed an operation, stored the GridFS binary, then died
        // before saving the VaultDocument: insert a real orphaned GridFS file plus a PROCESSING
        // VaultOperation referencing it, with an old createdAt.
        String gridFsFileId = gridFsFileStore.store(
                new MockMultipartFile("file", "orphan.jpg", MediaType.IMAGE_JPEG_VALUE, "orphan-bytes".getBytes()),
                userId, "stale-op-marker");

        VaultOperation stale = VaultOperation.builder()
                .userId(userId)
                .operation("vault.upload")
                .keyHash("stale-op-key-hash")
                .requestHash("stale-op-request-hash")
                .state(VaultOperationState.PROCESSING)
                .gridFsFileId(gridFsFileId)
                .createdAt(Instant.now().minus(15, ChronoUnit.MINUTES))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        VaultOperation saved = vaultOperationRepository.insert(stale);

        assertThat(countGridFsFilesForUser(userId)).isEqualTo(1);

        recoveryScheduler.recoverStaleOperations();

        assertThat(countGridFsFilesForUser(userId)).isEqualTo(0);
        VaultOperation reloaded = vaultOperationRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(VaultOperationState.FAILED);
    }
}
