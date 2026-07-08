package com.fintrack.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintrack.auth.web.dto.TokenResponse;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared MockMvc helpers for HTTP-level integration tests, following the
 * register/login/createAccount pattern already used in FintrackIntegrationTest
 * and PatAuthenticationIntegrationTest.
 */
public final class HttpTestHelper {

    private HttpTestHelper() {}

    public static String registerAndLogin(MockMvc mockMvc, ObjectMapper objectMapper, String email) throws Exception {
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

    public static TokenResponse registerAndLoginFull(MockMvc mockMvc, ObjectMapper objectMapper, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email, "password", "pass1234",
                                "firstName", "Test", "lastName", "User"))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), TokenResponse.class);
    }

    public static String createAccount(MockMvc mockMvc, ObjectMapper objectMapper, String jwt, String currency) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", currency + " Account", "accountType", "BANK",
                                "currency", currency, "initialBalance", "0"))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asText();
    }

    public static String createCategory(MockMvc mockMvc, ObjectMapper objectMapper, String jwt, String name, String transactionType) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/categories")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name, "transactionType", transactionType))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asText();
    }

    public static String createExpenseTransaction(
            MockMvc mockMvc, ObjectMapper objectMapper, String jwt,
            String accountId, String categoryId, String amount, String date
    ) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "transactionType", "EXPENSE",
                                "amount", amount,
                                "transactionDate", date,
                                "accountId", accountId,
                                "categoryId", categoryId))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asText();
    }
}
