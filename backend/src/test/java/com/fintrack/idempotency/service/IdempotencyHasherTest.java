package com.fintrack.idempotency.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyHasherTest {

    private final IdempotencyHasher hasher = new IdempotencyHasher();

    // ─── hashKey ────────────────────────────────────────────────────────────

    @Test
    void hashKey_isDeterministicAndLooksLikeSha256Hex() {
        String h1 = hasher.hashKey("some-raw-key-1234567890");
        String h2 = hasher.hashKey("some-raw-key-1234567890");

        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(64);
        assertThat(h1).matches("[0-9a-f]{64}");
    }

    @Test
    void hashKey_differentKeys_differentHashes() {
        assertThat(hasher.hashKey("key-one-1234567890"))
                .isNotEqualTo(hasher.hashKey("key-two-1234567890"));
    }

    // ─── hashJsonRequest ────────────────────────────────────────────────────

    private record Payload(String name, int amount) {}

    @Test
    void hashJsonRequest_sameLogicalPayload_differentFieldOrder_sameHash() {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("amount", 100);
        ordered.put("name", "coffee");

        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("name", "coffee");
        reordered.put("amount", 100);

        String h1 = hasher.hashJsonRequest("transaction.create", ordered);
        String h2 = hasher.hashJsonRequest("transaction.create", reordered);

        assertThat(h1).isEqualTo(h2);
    }

    @Test
    void hashJsonRequest_recordWithSameFields_matchesEquivalentMap() {
        Payload payload = new Payload("coffee", 100);
        Map<String, Object> asMap = new LinkedHashMap<>();
        asMap.put("amount", 100);
        asMap.put("name", "coffee");

        assertThat(hasher.hashJsonRequest("op", payload))
                .isEqualTo(hasher.hashJsonRequest("op", asMap));
    }

    @Test
    void hashJsonRequest_differentOperationName_differentHash() {
        Map<String, Object> body = Map.of("amount", 100);

        String h1 = hasher.hashJsonRequest("transaction.create", body);
        String h2 = hasher.hashJsonRequest("transaction.update", body);

        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void hashJsonRequest_differentPayload_differentHash() {
        String h1 = hasher.hashJsonRequest("op", Map.of("amount", 100));
        String h2 = hasher.hashJsonRequest("op", Map.of("amount", 200));

        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void hashJsonRequest_nullBody_isStableAndDistinctFromNonNull() {
        String h1 = hasher.hashJsonRequest("op", null);
        String h2 = hasher.hashJsonRequest("op", null);
        String h3 = hasher.hashJsonRequest("op", Map.of());

        assertThat(h1).isEqualTo(h2);
        assertThat(h1).isNotEqualTo(h3);
    }

    // ─── hashMultipartRequest ───────────────────────────────────────────────

    @Test
    void hashMultipartRequest_sameParamsDifferentInsertionOrder_sameHash() {
        Map<String, String> params1 = new LinkedHashMap<>();
        params1.put("accountId", "1");
        params1.put("label", "statement.ofx");

        Map<String, String> params2 = new LinkedHashMap<>();
        params2.put("label", "statement.ofx");
        params2.put("accountId", "1");

        byte[] file = "file-bytes".getBytes();

        assertThat(hasher.hashMultipartRequest("statement.upload", params1, file))
                .isEqualTo(hasher.hashMultipartRequest("statement.upload", params2, file));
    }

    @Test
    void hashMultipartRequest_differentFileBytes_differentHash() {
        Map<String, String> params = Map.of("accountId", "1");

        String h1 = hasher.hashMultipartRequest("statement.upload", params, "file-a".getBytes());
        String h2 = hasher.hashMultipartRequest("statement.upload", params, "file-b".getBytes());

        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void hashMultipartRequest_differentParams_differentHash() {
        byte[] file = "same-file".getBytes();

        String h1 = hasher.hashMultipartRequest("statement.upload", Map.of("accountId", "1"), file);
        String h2 = hasher.hashMultipartRequest("statement.upload", Map.of("accountId", "2"), file);

        assertThat(h1).isNotEqualTo(h2);
    }
}
