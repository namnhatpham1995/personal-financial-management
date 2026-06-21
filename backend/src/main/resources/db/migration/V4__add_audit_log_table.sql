-- V4: Transactional audit log stored in PostgreSQL (replaces best-effort MongoDB write)
CREATE TABLE audit_log (
    id             BIGSERIAL    PRIMARY KEY,
    user_id        BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    action         VARCHAR(100) NOT NULL,
    ts             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    correlation_id VARCHAR(255),
    meta           JSONB
);

CREATE INDEX idx_audit_log_user_ts ON audit_log (user_id, ts DESC);
