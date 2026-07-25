package com.fintrack.vault.service;

import com.fintrack.vault.domain.VaultDocument;
import com.fintrack.vault.domain.VaultDocumentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-time backfill of the top-level {@code accountId} field on STATEMENT documents that predate
 * it, sourced from their {@code payload.accountId}. Deliberately lazy rather than run eagerly at
 * application startup (e.g. via {@code ApplicationRunner}): most Spring test contexts in this
 * codebase boot with MongoDB unconfigured/unreachable (see
 * {@link VaultUploadIdempotencyCoordinator#indexesEnsured} for the same concern), so this is
 * triggered on first real use of an account-scoped vault operation instead.
 *
 * <p>Guarded twice: an in-process {@link AtomicBoolean} skips repeat lazy triggers within one
 * process, and a persisted marker document in {@code vault_migrations} skips the work entirely
 * across restarts once it has actually completed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VaultAccountIdBackfillService {

    private static final String MIGRATIONS_COLLECTION = "vault_migrations";
    private static final String MIGRATION_MARKER_ID = "backfill-statement-account-id";

    private final MongoTemplate mongoTemplate;

    private final AtomicBoolean ranThisProcess = new AtomicBoolean(false);

    public void ensureBackfillOnce() {
        if (!ranThisProcess.compareAndSet(false, true)) {
            return;
        }
        if (mongoTemplate.exists(Query.query(Criteria.where("_id").is(MIGRATION_MARKER_ID)), MIGRATIONS_COLLECTION)) {
            return;
        }
        runBackfill();
    }

    private void runBackfill() {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("type").is(VaultDocumentType.STATEMENT),
                new Criteria().orOperator(
                        Criteria.where("accountId").exists(false),
                        Criteria.where("accountId").is(null))));
        List<VaultDocument> candidates = mongoTemplate.find(query, VaultDocument.class);

        int backfilled = 0;
        for (VaultDocument doc : candidates) {
            Object rawAccountId = doc.getPayload() == null ? null : doc.getPayload().get("accountId");
            if (rawAccountId == null) {
                log.warn("Statement document {} has no payload.accountId to backfill from; leaving accountId null",
                        doc.getId());
                continue;
            }
            Long accountId = ((Number) rawAccountId).longValue();
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(doc.getId())),
                    Update.update("accountId", accountId),
                    VaultDocument.class);
            backfilled++;
        }
        log.info("Statement accountId backfill complete: {}/{} candidates backfilled", backfilled, candidates.size());

        try {
            mongoTemplate.insert(new Document("_id", MIGRATION_MARKER_ID).append("completedAt", Instant.now()),
                    MIGRATIONS_COLLECTION);
        } catch (DuplicateKeyException e) {
            // Another process won the race to record completion first; the backfill itself is
            // idempotent (re-running finds no more candidates), so this is harmless.
            log.debug("Statement accountId backfill marker already recorded by another process");
        }
    }
}
