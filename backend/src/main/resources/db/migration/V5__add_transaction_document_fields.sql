-- Nullable link from a PostgreSQL transaction back to its source vault document (MongoDB ObjectId as text)
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS source_document_id TEXT;

-- Import dedup key: deterministic hash of (accountId + date + amount + normalizedDescription)
-- Unique partial index so manual transactions (NULL key) are unaffected.
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS import_dedup_key VARCHAR(255);
CREATE UNIQUE INDEX IF NOT EXISTS idx_transactions_import_dedup_key
    ON public.transactions (import_dedup_key)
    WHERE import_dedup_key IS NOT NULL;
