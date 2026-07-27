-- V19: retain receipt ingestion history when a Vault document is reassigned.
ALTER TABLE agent_run ADD COLUMN invalidated_at TIMESTAMPTZ;
ALTER TABLE agent_run ADD COLUMN invalidation_reason VARCHAR(2000);

COMMENT ON COLUMN agent_run.invalidated_at IS
    'Timestamp at which the run became invalid because its source Vault document was reassigned.';
COMMENT ON COLUMN agent_run.invalidation_reason IS
    'Non-secret explanation for why the run was invalidated.';
