-- Analytics, budget and history queries filter transactions by user and date range.
-- The composite index serves those range scans; its user_id prefix also covers every
-- lookup the single-column idx_transactions_user_id served, so that index is dropped.
CREATE INDEX IF NOT EXISTS idx_transactions_user_date ON transactions (user_id, transaction_date);
DROP INDEX IF EXISTS idx_transactions_user_id;
