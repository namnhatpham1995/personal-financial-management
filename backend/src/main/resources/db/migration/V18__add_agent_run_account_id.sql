-- V18: bind each agent run to a single account, captured from the source vault document's
-- accountId at run-start time. Replaces per-proposal accountId as the authority for which
-- account a committed transaction lands in (rework-vault-upload-import PR5).
-- Nullable: runs created before this migration have no account on record.

ALTER TABLE agent_run ADD COLUMN account_id BIGINT REFERENCES accounts(id) ON DELETE RESTRICT;

COMMENT ON COLUMN agent_run.account_id IS
    'Account every non-excluded proposal in this run is committed against, captured from the source vault document''s accountId at start time. Null for runs created before this column existed.';
