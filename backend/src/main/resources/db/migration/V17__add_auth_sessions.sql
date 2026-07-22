-- ROLLBACK NOTE (manual DOWN steps; Flyway has no automatic down-migrations):
--   ALTER TABLE refresh_tokens DROP CONSTRAINT fk_refresh_tokens_session;
--   ALTER TABLE refresh_tokens DROP COLUMN session_id;
--   DROP INDEX IF EXISTS idx_auth_sessions_user_id;
--   DROP TABLE IF EXISTS auth_sessions;
-- Additive session data may remain during an application rollback.

CREATE TABLE auth_sessions (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    started_at          TIMESTAMPTZ  NOT NULL,
    last_activity_at    TIMESTAMPTZ  NOT NULL,
    absolute_expires_at TIMESTAMPTZ  NOT NULL,
    revoked_at          TIMESTAMPTZ
);

CREATE INDEX idx_auth_sessions_user_id ON auth_sessions(user_id);

ALTER TABLE refresh_tokens ADD COLUMN session_id BIGINT;

-- Existing refresh rows do not carry session lineage. Give each row its own bounded session so
-- the new foreign key can be enforced without silently merging independent browser logins.
DO $$
DECLARE
    token_row RECORD;
    new_session_id BIGINT;
BEGIN
    FOR token_row IN SELECT id, user_id, created_at, revoked, rotated_at FROM refresh_tokens LOOP
        INSERT INTO auth_sessions (user_id, started_at, last_activity_at, absolute_expires_at, revoked_at)
        VALUES (
            token_row.user_id,
            token_row.created_at,
            token_row.created_at,
            token_row.created_at + INTERVAL '30 days',
            CASE WHEN token_row.revoked THEN COALESCE(token_row.rotated_at, token_row.created_at) ELSE NULL END
        )
        RETURNING id INTO new_session_id;

        UPDATE refresh_tokens SET session_id = new_session_id WHERE id = token_row.id;
    END LOOP;
END $$;

ALTER TABLE refresh_tokens ALTER COLUMN session_id SET NOT NULL;
ALTER TABLE refresh_tokens ADD CONSTRAINT fk_refresh_tokens_session
    FOREIGN KEY (session_id) REFERENCES auth_sessions(id) ON DELETE CASCADE;
CREATE INDEX idx_refresh_tokens_session_id ON refresh_tokens(session_id);
