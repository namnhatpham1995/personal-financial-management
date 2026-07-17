package com.fintrack.idempotency.service;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * SHA-256-based hashing for idempotency keys and request payloads.
 *
 * <p>Never logs raw keys or raw request bodies — callers must not pass these values to a logger
 * either.
 */
@Component
public class IdempotencyHasher {

    /**
     * Dedicated canonicalizing mapper: sorts object properties and map entries alphabetically so
     * two logically-equal payloads with different field/insertion order hash identically.
     */
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** SHA-256 hex digest of the raw idempotency key. Never log {@code rawKey}. */
    public String hashKey(String rawKey) {
        return sha256Hex(rawKey.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Canonical hash covering the logical operation name plus a normalized (key-sorted) JSON
     * serialization of the request body, so field/map ordering never changes the hash. Never log
     * {@code requestBody}.
     */
    public String hashJsonRequest(String operation, Object requestBody) {
        String canonicalJson = toCanonicalJson(requestBody);
        String combined = operation + " " + canonicalJson;
        return sha256Hex(combined.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Hash covering the logical operation name, the non-file parameters (sorted by key), and the
     * raw file bytes. Used by multipart uploads (vault/statement) — not wired to any controller
     * yet. Never log {@code nonFileParams} or {@code fileBytes}.
     */
    public String hashMultipartRequest(String operation, Map<String, String> nonFileParams, byte[] fileBytes) {
        SortedMap<String, String> sorted = new TreeMap<>(nonFileParams == null ? Map.of() : nonFileParams);
        StringBuilder sb = new StringBuilder(operation).append(' ');
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(sb.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            if (fileBytes != null) {
                digest.update(fileBytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String toCanonicalJson(Object requestBody) {
        try {
            if (requestBody == null) {
                return "null";
            }
            return CANONICAL_MAPPER.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to canonicalize request body for idempotency hashing", e);
        }
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
