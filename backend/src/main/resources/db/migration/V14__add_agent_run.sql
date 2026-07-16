-- V14: agent_run table for the receipt ingestion agent's run lifecycle.
-- Additive only; no existing tables touched. Backend is system of record for run
-- state — the agent service holds only its own graph checkpoint state.

CREATE TABLE agent_run (
    id                       BIGSERIAL PRIMARY KEY,
    user_id                  BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    vault_document_id        VARCHAR(24)  NOT NULL,
    status                   VARCHAR(32)  NOT NULL,
    extraction               JSONB,
    proposals                JSONB,
    failure_reason           VARCHAR(2000),
    retryable                BOOLEAN      NOT NULL DEFAULT FALSE,
    created_transaction_ids  JSONB,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_agent_run_user_status ON agent_run (user_id, status);
CREATE INDEX idx_agent_run_vault_document ON agent_run (vault_document_id);

COMMENT ON TABLE agent_run IS
    'Lifecycle record for an LLM-driven receipt ingestion run. status: EXTRACTING -> AWAITING_REVIEW -> COMMITTED | REJECTED | FAILED.';
COMMENT ON COLUMN agent_run.vault_document_id IS
    'MongoDB ObjectId (hex string) of the source vault receipt document.';
COMMENT ON COLUMN agent_run.extraction IS
    'LLM vision extraction result: merchant, date, currency, line items, total.';
COMMENT ON COLUMN agent_run.proposals IS
    'Proposed transactions after categorization + deterministic validation, with reviewer-visible flags.';
COMMENT ON COLUMN agent_run.retryable IS
    'Set when status = FAILED and the failure is transient (e.g. LLM outage) — the user may start a new run.';
COMMENT ON COLUMN agent_run.created_transaction_ids IS
    'Transaction ids created on COMMITTED, stored so retried commits return the same result idempotently.';
