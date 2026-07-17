-- ROLLBACK NOTE (manual DOWN steps — Flyway has no automatic down-migrations):
--   1. DROP INDEX IF EXISTS uq_transactions_user_import_dedup_key;
--   2. ALTER TABLE refresh_tokens DROP COLUMN IF EXISTS successor_id;
--      ALTER TABLE refresh_tokens DROP COLUMN IF EXISTS rotated_at;
--   3. DROP INDEX IF EXISTS uq_api_tokens_user_idempotency_key_hash;
--      ALTER TABLE api_tokens DROP COLUMN IF EXISTS idempotency_key_hash;
--      ALTER TABLE api_tokens DROP COLUMN IF EXISTS request_hash;
--   4. DROP TABLE IF EXISTS idempotency_operations;
--   Additive columns/tables are safe to leave in place on mixed-version rollback; only drop them
--   if a genuinely older application version cannot tolerate their presence.

-- ─── Preflight: refuse to proceed if pre-existing data would violate the new
--     composite unique index on transactions(user_id, import_dedup_key). ────────
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
        RAISE EXCEPTION 'V15 migration aborted: % (user_id, import_dedup_key) pair(s) already have duplicate rows; resolve these transactions before creating uq_transactions_user_import_dedup_key', dup_count;
    END IF;
END $$;

-- ─── Idempotency operations (PostgreSQL-backed JSON creates) ──────────────────
CREATE TABLE idempotency_operations (
    id                     BIGSERIAL     PRIMARY KEY,
    user_id                BIGINT        NOT NULL REFERENCES users(id),
    operation              VARCHAR(100)  NOT NULL,
    key_hash               VARCHAR(64)   NOT NULL,
    request_hash           VARCHAR(64)   NOT NULL,
    state                  VARCHAR(20)   NOT NULL,
    response_status        INTEGER,
    response_body          TEXT,
    response_content_type  VARCHAR(100),
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at           TIMESTAMPTZ,
    expires_at             TIMESTAMPTZ   NOT NULL
);

CREATE UNIQUE INDEX uq_idempotency_operations_user_operation_key
    ON idempotency_operations (user_id, operation, key_hash);

-- Supports the future cleanup job (later change) that sweeps expired rows.
CREATE INDEX idx_idempotency_operations_expires_at
    ON idempotency_operations (expires_at);

-- ─── api_tokens: additive idempotency metadata for PAT creation (group 5) ──────
ALTER TABLE api_tokens ADD COLUMN idempotency_key_hash VARCHAR(64);
ALTER TABLE api_tokens ADD COLUMN request_hash VARCHAR(64);

CREATE UNIQUE INDEX uq_api_tokens_user_idempotency_key_hash
    ON api_tokens (user_id, idempotency_key_hash)
    WHERE idempotency_key_hash IS NOT NULL;

-- ─── refresh_tokens: additive rotation lineage metadata (group 5) ──────────────
ALTER TABLE refresh_tokens ADD COLUMN successor_id BIGINT REFERENCES refresh_tokens(id);
ALTER TABLE refresh_tokens ADD COLUMN rotated_at TIMESTAMPTZ;

-- ─── transactions: user-scoped import-dedup uniqueness (group 3 prep) ──────────
-- The existing global unique index idx_transactions_import_dedup_key (from V5) is intentionally
-- retained here; dropping it happens in a later change once duplicate/migration safety is proven.
CREATE UNIQUE INDEX uq_transactions_user_import_dedup_key
    ON transactions (user_id, import_dedup_key)
    WHERE import_dedup_key IS NOT NULL;
