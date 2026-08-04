package com.fintrack.vault.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit coverage for {@link VaultAccountIdBackfillService}'s trigger-once guard, focused on the
 * fail-closed retry behavior: a transient failure must release the in-process guard so the next
 * call retries, rather than leaving the backfill permanently un-attempted for the process's life.
 */
@ExtendWith(MockitoExtension.class)
class VaultAccountIdBackfillServiceTest {

    @Mock MongoTemplate mongoTemplate;
    @InjectMocks VaultAccountIdBackfillService backfillService;

    @Test
    void attemptFails_retriedOnNextCall_ratherThanSkippingBackfillForever() {
        when(mongoTemplate.exists(any(Query.class), eq("vault_migrations")))
                .thenThrow(new RuntimeException("mongo temporarily unavailable"))
                .thenReturn(true);

        // First call: the marker existence check fails, so the trigger flag must be released.
        assertThatThrownBy(() -> backfillService.ensureBackfillOnce())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("mongo temporarily unavailable");

        // Second call: retried rather than silently no-op'd, and this time the marker already
        // exists, so the backfill itself is skipped without touching find/update.
        backfillService.ensureBackfillOnce();

        verify(mongoTemplate, times(2)).exists(any(Query.class), eq("vault_migrations"));
        verify(mongoTemplate, never()).find(any(Query.class), eq(com.fintrack.vault.domain.VaultDocument.class));
    }

    @Test
    void markerAlreadyExists_secondCallIsANoOp() {
        when(mongoTemplate.exists(any(Query.class), eq("vault_migrations"))).thenReturn(true);

        backfillService.ensureBackfillOnce();
        backfillService.ensureBackfillOnce();

        // The in-process flag latches after a successful attempt (marker found), so the second
        // call short-circuits before touching Mongo again.
        verify(mongoTemplate, times(1)).exists(any(Query.class), eq("vault_migrations"));
    }

    @Test
    void insertRaceOnMarker_isSwallowedAsHarmless() {
        when(mongoTemplate.exists(any(Query.class), eq("vault_migrations"))).thenReturn(false);
        when(mongoTemplate.find(any(Query.class), eq(com.fintrack.vault.domain.VaultDocument.class)))
                .thenReturn(java.util.List.of());
        when(mongoTemplate.insert(any(org.bson.Document.class), eq("vault_migrations")))
                .thenThrow(new DuplicateKeyException("already recorded by another process"));

        backfillService.ensureBackfillOnce();

        verify(mongoTemplate).insert(any(org.bson.Document.class), eq("vault_migrations"));
    }
}
