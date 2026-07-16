package com.fintrack.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.agent.service.AgentTokenService;
import com.fintrack.auth.repository.UserRepository;
import com.fintrack.support.HttpTestHelper;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-lifecycle integration test for the receipt ingestion agent run: start -> agent posts
 * proposals -> user decides -> commit. Since the LangGraph agent-service (task group 2) isn't
 * part of this backend module, the "agent" side of the conversation is simulated by minting an
 * agent-scoped token directly (same mechanism AgentRunService uses) and calling the agent-token
 * endpoints exactly as the real agent-service would over HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class AgentRunLifecycleIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_agent_test")
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
        registry.add("spring.data.mongodb.uri", () -> mongo.getReplicaSetUrl("fintrack_agent_test"));
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
        // Non-blank so the feature isn't reported dark; unreachable is fine — notifications
        // to the agent-service are best-effort and this test drives the agent side itself.
        registry.add("app.agent.service-url", () -> "http://localhost:1");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AgentTokenService agentTokenService;
    @Autowired UserRepository userRepository;

    private String uploadReceipt(String jwt) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "receipt.jpg", MediaType.IMAGE_JPEG_VALUE, "fake-image-bytes".getBytes(StandardCharsets.UTF_8));
        MvcResult result = mockMvc.perform(multipart("/api/vault/upload")
                        .file(file)
                        .param("type", "RECEIPT")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String agentTokenFor(String email, Long runId) {
        Long userId = userRepository.findByEmail(email).orElseThrow().getId();
        return agentTokenService.generateAgentToken(email, userId, runId);
    }

    private Long startRun(String jwt, String vaultDocumentId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/agent-runs")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("vaultDocumentId", vaultDocumentId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXTRACTING"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private String proposalPayload(String accountId, String categoryId) {
        return """
                {
                  "extraction": {"merchant": "Corner Store", "total": "12.50"},
                  "proposals": [
                    {"merchant": "Corner Store", "date": "2026-01-05", "amount": "12.50",
                     "currency": "USD", "categoryId": %s, "accountId": %s,
                     "description": "Groceries", "flags": [], "excluded": false}
                  ]
                }
                """.formatted(categoryId, accountId);
    }

    @Test
    void fullLifecycle_startProposalsApprove_createsTransactionAndAdjustsBalance() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "agent.happy@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String categoryId = HttpTestHelper.createCategory(mockMvc, objectMapper, jwt, "Groceries", "EXPENSE");
        String docId = uploadReceipt(jwt);

        Long runId = startRun(jwt, docId);
        String agentToken = agentTokenFor("agent.happy@test.com", runId);

        mockMvc.perform(post("/api/v1/agent-runs/" + runId + "/proposals")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposalPayload(accountId, categoryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_REVIEW"))
                .andExpect(jsonPath("$.proposals[0].flags").isEmpty());

        MvcResult decisionResult = mockMvc.perform(post("/api/v1/agent-runs/" + runId + "/decision")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "approve", true,
                                "proposals", List.of(Map.of(
                                        "merchant", "Corner Store", "date", "2026-01-05",
                                        "amount", "12.50", "currency", "USD",
                                        "categoryId", Long.parseLong(categoryId), "accountId", Long.parseLong(accountId),
                                        "description", "Groceries", "flags", List.of(), "excluded", false))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andReturn();
        JsonNode decision = objectMapper.readTree(decisionResult.getResponse().getContentAsString());
        assertThat(decision.get("createdTransactionIds")).hasSize(1);

        MvcResult accountResult = mockMvc.perform(get("/api/v1/accounts/" + accountId)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        BigDecimal balance = new BigDecimal(objectMapper.readTree(accountResult.getResponse().getContentAsString())
                .get("currentBalance").asText());
        assertThat(balance).isEqualByComparingTo(new BigDecimal("-12.50"));

        MvcResult txResult = mockMvc.perform(get("/api/v1/transactions")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(txResult.getResponse().getContentAsString()).get("content")).hasSize(1);

        MvcResult activityResult = mockMvc.perform(get("/api/v1/activity")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(activityResult.getResponse().getContentAsString()).get("content").size())
                .isGreaterThan(0);

        // Retried decision (duplicate submission) must not create a second transaction.
        mockMvc.perform(post("/api/v1/agent-runs/" + runId + "/decision")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("approve", true, "proposals", List.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"));

        MvcResult txResultAfterRetry = mockMvc.perform(get("/api/v1/transactions")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(txResultAfterRetry.getResponse().getContentAsString()).get("content")).hasSize(1);
    }

    @Test
    void rejection_leavesNoTransactions() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "agent.reject@test.com");
        String accountId = HttpTestHelper.createAccount(mockMvc, objectMapper, jwt, "USD");
        String categoryId = HttpTestHelper.createCategory(mockMvc, objectMapper, jwt, "Groceries", "EXPENSE");
        String docId = uploadReceipt(jwt);

        Long runId = startRun(jwt, docId);
        String agentToken = agentTokenFor("agent.reject@test.com", runId);
        mockMvc.perform(post("/api/v1/agent-runs/" + runId + "/proposals")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(proposalPayload(accountId, categoryId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/agent-runs/" + runId + "/decision")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("approve", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        MvcResult txResult = mockMvc.perform(get("/api/v1/transactions")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(txResult.getResponse().getContentAsString()).get("content")).isEmpty();
    }

    @Test
    void startRun_onAnotherUsersDocument_returnsNotFound() throws Exception {
        String ownerJwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "agent.owner@test.com");
        String docId = uploadReceipt(ownerJwt);

        String otherJwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "agent.other@test.com");
        mockMvc.perform(post("/api/v1/agent-runs")
                        .header("Authorization", "Bearer " + otherJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("vaultDocumentId", docId))))
                .andExpect(status().isNotFound());
    }

    @Test
    void startRun_whileAnotherRunActive_returnsConflict() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "agent.conflict@test.com");
        String docId = uploadReceipt(jwt);

        mockMvc.perform(post("/api/v1/agent-runs")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("vaultDocumentId", docId))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/agent-runs")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("vaultDocumentId", docId))))
                .andExpect(status().isConflict());
    }

    @Test
    void decision_onRunStillExtracting_returnsConflict() throws Exception {
        String jwt = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "agent.notready@test.com");
        String docId = uploadReceipt(jwt);
        Long runId = startRun(jwt, docId);

        mockMvc.perform(post("/api/v1/agent-runs/" + runId + "/decision")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("approve", true, "proposals", List.of()))))
                .andExpect(status().isConflict());
    }

    @Test
    void agentToken_cannotAccessNonAgentEndpointsOrOtherRuns() throws Exception {
        String jwtA = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "agent.tokenA@test.com");
        String docA = uploadReceipt(jwtA);
        Long runA = startRun(jwtA, docA);
        String tokenA = agentTokenFor("agent.tokenA@test.com", runA);

        // Cannot call a completely unrelated authenticated endpoint.
        mockMvc.perform(get("/api/v1/transactions")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isForbidden());

        // Cannot use its token against a different run's proposals endpoint.
        String jwtB = HttpTestHelper.registerAndLogin(mockMvc, objectMapper, "agent.tokenB@test.com");
        String docB = uploadReceipt(jwtB);
        Long runB = startRun(jwtB, docB);

        mockMvc.perform(post("/api/v1/agent-runs/" + runB + "/proposals")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extraction\":{},\"proposals\":[]}"))
                .andExpect(status().isForbidden());
    }
}
