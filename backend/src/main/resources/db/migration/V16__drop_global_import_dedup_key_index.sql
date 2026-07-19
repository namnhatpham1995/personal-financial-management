-- ROLLBACK NOTE (manual DOWN steps — Flyway has no automatic down-migrations):
--   1. Recreate the global unique index ONLY if no cross-user duplicate import_dedup_key
--      literals have been created since this migration ran (they are expected to exist once this
--      version is live, since user-scoped uniqueness is the whole point):
--        CREATE UNIQUE INDEX idx_transactions_import_dedup_key
--            ON public.transactions (import_dedup_key)
--            WHERE import_dedup_key IS NOT NULL;
--      Verify first: SELECT import_dedup_key, COUNT(DISTINCT user_id) FROM transactions
--        WHERE import_dedup_key IS NOT NULL GROUP BY import_dedup_key HAVING COUNT(DISTINCT user_id) > 1;
--      A non-empty result means recreating the global index would fail (or silently mask
--      legitimate same-key-different-user rows) — do not roll back in that case.

-- ─── Preflight: re-confirm no duplicate (user_id, import_dedup_key) pairs exist ────
-- Defensive re-check: V15 already validated this and already created the composite unique index
-- this migration relies on, so this should be a no-op — cheap insurance against data added since.
DO $$
DECLARE
    dup_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO dup_count
    FROM (
        SELECT user_id, import_dedup_key
        FROM transactions
        WHERE import_dedup_key IS NOT NULL
        GROUP BY user_id, import_dedup_key
        HAVING COUNT(*) > 1
    ) dupes;

    IF dup_count > 0 THEN
        RAISE EXCEPTION 'V16 migration aborted: % (user_id, import_dedup_key) pair(s) violate uq_transactions_user_import_dedup_key; resolve before dropping the legacy global index', dup_count;
    END IF;
END $$;

-- ─── Drop the legacy V5 global unique index ────────────────────────────────────
-- Uniqueness is now enforced solely by V15's uq_transactions_user_import_dedup_key composite
-- partial index on (user_id, import_dedup_key), so two different users may legitimately share the
-- same import_dedup_key literal (e.g. deterministic statement-row fingerprints that happen to
-- collide across unrelated accounts).
DROP INDEX IF EXISTS idx_transactions_import_dedup_key;
