package com.fintrack.vault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.support.HttpTestHelper;
import com.fintrack.vault.web.dto.ConfirmImportRequest;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration coverage for the unified Vault workspace listing and stage-counts endpoints,
 * which join MongoDB-backed vault documents with PostgreSQL-backed agent ingestion runs.
 * See {@link StatementImportPipelineIntegrationTest} and
 * {@code com.fintrack.agent.AgentRunLifecycleIntegrationTest} for the underlying pipelines this
 * test drives to reach each derived stage.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class VaultWorkspaceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_vault_workspace_test")
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
        registry.add("spring.data.mongodb.uri", () -> mongo.getReplicaSetUrl("fintrack_vault_workspace_test"));
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
        registry.add("app.agent.service-url", () -> "http://localhost:1");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired com.fintrack.agent.service.AgentTokenService agentTokenService;
    @Autowired com.fintrack.auth.repository.UserRepository userRepository;

    private static final String VALID_CSV =
            "Date,Description,Amount\n" +
            "2026-01-05,Salary,1000.00\n" +
            "2026-01-10,Groceries,-40.00\n";

    private String uploadStatement(String jwt, String accountId) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "statement.csv", MediaType.TEXT_PLAIN_VALUE,
                VALID_CSV.getBytes(StandardCharsets.UTF_8));
        MvcResult result = mockMvc.perform(multipart("/api/vault/import/upload")
                        .file(file)
                        .param("accountId", accountId)
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString()))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("documentId").asText();
    }

    private void parseStatement(String jwt, String documentId) throws Exception {
        mockMvc.perform(post("/api/vault/import/" + documentId + "/parse")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());
    }

    private void confirmStatement(String jwt, String documentId) throws Exception {
        JsonNode rows = objectMapper.readTree(mockMvc.perform(get("/api/vault/import/" + documentId + "/rows")
                        .header("Authorization", "Bearer " + jwt)).andReturn().getResponse().getContentAsString());
        List<String> keys = new ArrayList<>();
        rows.forEach(row -> keys.add(row.get("dedupKey").asText()));
        ConfirmImportRequest body = new ConfirmImportRequest(
                keys.stream().map(k -> new ConfirmImportRequest.ConfirmRow(k, null)).toList());
        mockMvc.perform(post("/api/vault/import/" + documentId + "/confirm")
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private String uploadReceipt(String jwt, String accountId) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.jpg", MediaType.IMAGE_JPEG_VALUE, "fake-image-bytes".getBytes(StandardCharsets.UTF_8));
        MvcResult result = mockMvc.perform(multipart("/api/vault/upload")
                        .file(file)
                        .param("type", "RECEIPT")
                        .param("accountId", accountId)
                        .header("Authorization", "Bearer " + jwt)
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString()))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private Long startIngestion(String jwt, String vaultDocumentId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/agent-runs")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vaultDocumentId\":\"" + vaultDocumentId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void unfilteredWorkspace_listsBothDocumentTypesTogether() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "workspace.mixed@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        uploadStatement(jwt, accountId);
        uploadReceipt(jwt, accountId);

        MvcResult result = mockMvc.perform(get("/api/vault/workspace")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("content")).hasSize(2);
        List<String> types = new ArrayList<>();
        body.get("content").forEach(doc -> types.add(doc.get("type").asText()));
        assertThat(types).containsExactlyInAnyOrder("STATEMENT", "RECEIPT");
    }

    @Test
    void stageFilter_needsReview_matchesBothStatementAndReceiptSources() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "workspace.needsreview@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");

        // Statement -> STAGED (needs review).
        String statementId = uploadStatement(jwt, accountId);
        parseStatement(jwt, statementId);

        // Receipt -> AWAITING_REVIEW (needs review).
        String categoryId = HttpTestHelper.createCategory(mockMvc, objectMapper, jwt, "Groceries", "EXPENSE");
        String receiptId = uploadReceipt(jwt, accountId);
        Long runId = startIngestion(jwt, receiptId);
        String agentToken = agentTokenFor("workspace.needsreview@test.com", runId);
        mockMvc.perform(post("/api/v1/agent-runs/" + runId + "/proposals")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "extraction": {"merchant": "Corner Store", "total": "12.50"},
                                  "proposals": [
                                    {"merchant": "Corner Store", "date": "2026-01-05", "amount": "12.50",
                                     "currency": "USD", "categoryId": %s,
                                     "description": "Groceries", "flags": [], "excluded": false}
                                  ]
                                }
                                """.formatted(categoryId)))
                .andExpect(status().isOk());

        // A third document that is NOT in review (statement stays UPLOADED / ready to import).
        uploadStatement(jwt, accountId);

        MvcResult result = mockMvc.perform(get("/api/vault/workspace")
                        .param("stage", "NEEDS_REVIEW")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> ids = new ArrayList<>();
        body.get("content").forEach(doc -> ids.add(doc.get("id").asText()));
        assertThat(ids).containsExactlyInAnyOrder(statementId, receiptId);
    }

    @Test
    void combinedTypeStageAndAccountFilters_narrowsPrecisely() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "workspace.combined@test.com");
        String accountA = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String accountB = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");

        String matching = uploadStatement(jwt, accountA); // STATEMENT, UPLOADED, accountA
        uploadStatement(jwt, accountB); // wrong account
        uploadReceipt(jwt, accountA); // wrong type

        MvcResult result = mockMvc.perform(get("/api/vault/workspace")
                        .param("type", "STATEMENT")
                        .param("stage", "READY_TO_IMPORT")
                        .param("accountId", accountA)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("content")).hasSize(1);
        assertThat(body.get("content").get(0).get("id").asText()).isEqualTo(matching);
    }

    @Test
    void pagination_underStageFilter_reflectsFullMatchingCollection() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "workspace.paginate@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        uploadStatement(jwt, accountId);
        uploadStatement(jwt, accountId);
        uploadStatement(jwt, accountId);
        uploadReceipt(jwt, accountId); // does not match READY_TO_IMPORT

        MvcResult result = mockMvc.perform(get("/api/vault/workspace")
                        .param("stage", "READY_TO_IMPORT")
                        .param("size", "1")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("content")).hasSize(1);
        assertThat(body.get("totalElements").asInt()).isEqualTo(3);
        assertThat(body.get("totalPages").asInt()).isEqualTo(3);
    }

    @Test
    void unknownStage_rejectedAsBadRequest() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "workspace.badstage@test.com");
        mockMvc.perform(get("/api/vault/workspace")
                        .param("stage", "NOT_A_REAL_STAGE")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isBadRequest());
    }

    @Test
    void stageCounts_spanWholeCollectionNotJustOnePage() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "workspace.counts@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        uploadStatement(jwt, accountId);
        uploadStatement(jwt, accountId);
        uploadStatement(jwt, accountId);
        uploadReceipt(jwt, accountId);

        MvcResult result = mockMvc.perform(get("/api/vault/workspace/counts")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("counts").get("READY_TO_IMPORT").asInt()).isEqualTo(3);
        assertThat(body.get("counts").get("NOT_PROCESSED").asInt()).isEqualTo(1);
    }

    @Test
    void stageCounts_respectAccountFilter() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "workspace.countsacct@test.com");
        String accountA = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String accountB = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        uploadStatement(jwt, accountA);
        uploadStatement(jwt, accountB);
        uploadStatement(jwt, accountB);

        MvcResult result = mockMvc.perform(get("/api/vault/workspace/counts")
                        .param("accountId", accountB)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("counts").get("READY_TO_IMPORT").asInt()).isEqualTo(2);
    }

    @Test
    void workspaceListingAndCounts_areUserIsolated() throws Exception {
        String jwtA = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "workspace.isoA@test.com");
        String accountA = HttpTestHelper.createAccount(mockMvc, objectMapper, jwtA, "USD");
        uploadStatement(jwtA, accountA);

        String jwtB = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "workspace.isoB@test.com");

        MvcResult listResult = mockMvc.perform(get("/api/vault/workspace")
                        .header("Authorization", "Bearer " + jwtB))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(listResult.getResponse().getContentAsString()).get("content")).isEmpty();

        MvcResult countsResult = mockMvc.perform(get("/api/vault/workspace/counts")
                        .header("Authorization", "Bearer " + jwtB))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(countsResult.getResponse().getContentAsString()).get("counts").size())
                .isZero();
    }

    private String agentTokenFor(String email, Long runId) {
        Long userId = userRepository.findByEmail(email).orElseThrow().getId();
        return agentTokenService.generateAgentToken(email, userId, runId);
    }
}
