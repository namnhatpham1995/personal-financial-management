CREATE TABLE api_tokens (
    id            BIGSERIAL     PRIMARY KEY,
    user_id       BIGINT        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name          VARCHAR(100)  NOT NULL,
    token_hash    VARCHAR(255)  NOT NULL UNIQUE,
    token_prefix  VARCHAR(32)   NOT NULL,
    scope         VARCHAR(20)   NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMPTZ   NOT NULL,
    revoked_at    TIMESTAMPTZ,
    last_used_at  TIMESTAMPTZ
);

CREATE INDEX idx_api_tokens_user_id ON api_tokens (user_id);
