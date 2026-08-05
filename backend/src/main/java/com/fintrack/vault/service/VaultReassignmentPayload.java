package com.fintrack.vault.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed view over {@code VaultOperation.payload} for the {@code vault.reassign} operation. The
 * underlying Mongo field stays {@code Map<String, Object>} — it is shared with the upload flow via
 * {@code VaultOperation}, and {@code VaultReassignmentOperationMigration} still writes it directly
 * from a legacy collection — so {@link #toMap()}/{@link #fromMap} translate at that boundary.
 *
 * <p>The field names below must stay byte-identical to what earlier deployments wrote: a renamed
 * key would fail to deserialize an in-flight {@code PROCESSING} row, and {@link #DOCUMENT_ID}/
 * {@link #TARGET_ACCOUNT_ID} specifically also feed the request-hash input, so a rename there would
 * turn a legitimate replay into a false conflict.
 */
record VaultReassignmentPayload(
        String documentId,
        Long targetAccountId,
        Long sourceAccountId,
        List<Long> removedTransactionIds,
        Integer removedTransactionCount,
        Boolean manualLinkDetached,
        String failureReason) {

    static final String DOCUMENT_ID = "documentId";
    static final String TARGET_ACCOUNT_ID = "targetAccountId";
    private static final String SOURCE_ACCOUNT_ID = "sourceAccountId";
    private static final String REMOVED_TRANSACTION_IDS = "removedTransactionIds";
    private static final String REMOVED_TRANSACTION_COUNT = "removedTransactionCount";
    private static final String MANUAL_LINK_DETACHED = "manualLinkDetached";
    private static final String FAILURE_REASON = "failureReason";

    static VaultReassignmentPayload initial(String documentId, Long targetAccountId) {
        return new VaultReassignmentPayload(documentId, targetAccountId, null, List.of(), null, null, null);
    }

    /** Parses a stored (or legacy pre-typed) payload map, coercing numeric types defensively. */
    static VaultReassignmentPayload fromMap(Map<String, Object> map) {
        if (map == null) {
            return initial(null, null);
        }
        return new VaultReassignmentPayload(
                (String) map.get(DOCUMENT_ID),
                asLong(map.get(TARGET_ACCOUNT_ID)),
                asLong(map.get(SOURCE_ACCOUNT_ID)),
                asLongList(map.get(REMOVED_TRANSACTION_IDS)),
                asInteger(map.get(REMOVED_TRANSACTION_COUNT)),
                (Boolean) map.get(MANUAL_LINK_DETACHED),
                (String) map.get(FAILURE_REASON));
    }

    /** Omits unset fields, matching what the pre-typed code left out of the stored map. */
    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        putIfPresent(map, DOCUMENT_ID, documentId);
        putIfPresent(map, TARGET_ACCOUNT_ID, targetAccountId);
        putIfPresent(map, SOURCE_ACCOUNT_ID, sourceAccountId);
        if (removedTransactionIds != null && !removedTransactionIds.isEmpty()) {
            map.put(REMOVED_TRANSACTION_IDS, removedTransactionIds);
        }
        putIfPresent(map, REMOVED_TRANSACTION_COUNT, removedTransactionCount);
        putIfPresent(map, MANUAL_LINK_DETACHED, manualLinkDetached);
        putIfPresent(map, FAILURE_REASON, failureReason);
        return map;
    }

    VaultReassignmentPayload withClaimDetails(Long sourceAccountId, List<Long> removedTransactionIds,
                                              int removedTransactionCount, boolean manualLinkDetached) {
        return new VaultReassignmentPayload(documentId, targetAccountId, sourceAccountId,
                removedTransactionIds, removedTransactionCount, manualLinkDetached, failureReason);
    }

    VaultReassignmentPayload withRemovedTransactionCount(int removedTransactionCount) {
        return new VaultReassignmentPayload(documentId, targetAccountId, sourceAccountId,
                removedTransactionIds, removedTransactionCount, manualLinkDetached, failureReason);
    }

    VaultReassignmentPayload withFailureReason(String failureReason) {
        return new VaultReassignmentPayload(documentId, targetAccountId, sourceAccountId,
                removedTransactionIds, removedTransactionCount, manualLinkDetached, failureReason);
    }

    int removedTransactionCountOrZero() {
        return removedTransactionCount == null ? 0 : removedTransactionCount;
    }

    boolean manualLinkDetachedOrFalse() {
        return Boolean.TRUE.equals(manualLinkDetached);
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(value));
    }

    private static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.intValue() : Integer.valueOf(String.valueOf(value));
    }

    private static List<Long> asLongList(Object raw) {
        if (!(raw instanceof Iterable<?> values)) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (Object value : values) {
            ids.add(asLong(value));
        }
        return ids;
    }
}
