package com.fintrack.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exports the live springdoc OpenAPI spec to target/openapi.json (task 1.1). CI diffs this
 * file against the committed mcp-server/openapi.json snapshot to catch backend contract
 * changes that were not propagated to the MCP server's generated types.
 *
 * Key order is normalized (recursively sorted) and volatile fields ("servers", springdoc
 * build metadata) are stripped so the export is deterministic across runs/environments.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class OpenApiSpecExportTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintrack_openapi_export")
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
        registry.add("spring.data.mongodb.uri", () -> "mongodb://localhost:27017/fintrack_openapi_export_unused");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void exportsNormalizedOpenApiSpec() throws Exception {
        String rawBody = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = objectMapper.readTree(rawBody);
        assertThat(root.has("paths")).isTrue();

        ObjectNode normalized = (ObjectNode) normalize(root);
        // Volatile across environments/builds — not part of the contract being pinned.
        normalized.remove("servers");

        Path outputPath = Path.of("target", "openapi.json");
        Files.createDirectories(outputPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), normalized);

        assertThat(outputPath).exists();
    }

    /** Recursively sorts object keys so the exported JSON is stable regardless of declaration order. */
    private JsonNode normalize(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                sorted.put(entry.getKey(), normalize(entry.getValue()));
            }
            ObjectNode result = objectMapper.createObjectNode();
            sorted.forEach(result::set);
            return result;
        }
        if (node.isArray()) {
            var result = objectMapper.createArrayNode();
            node.forEach(child -> result.add(normalize(child)));
            return result;
        }
        return node;
    }
}
