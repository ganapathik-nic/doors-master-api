-- Weekly access is owned by the API user and covers all keys under their mapped Agent.
-- Existing date-specific client calendars remain intact and continue to apply.
CREATE TABLE api_user_weekly_access (
    user_id INTEGER PRIMARY KEY REFERENCES users(user_id),
    restricted BOOLEAN NOT NULL DEFAULT FALSE,
    slots JSONB NOT NULL DEFAULT '[]',
    version BIGINT NOT NULL,
    updated_by VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE api_user_weekly_access_history (
    history_id BIGSERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(user_id),
    version BIGINT NOT NULL,
    snapshot JSONB NOT NULL,
    changed_by VARCHAR(255) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, version)
);
