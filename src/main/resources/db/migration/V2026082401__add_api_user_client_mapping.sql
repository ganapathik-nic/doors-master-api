ALTER TABLE users
    ADD COLUMN IF NOT EXISTS api_client_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_users_api_client_id
    ON users (api_client_id);

ALTER TABLE users
    DROP CONSTRAINT IF EXISTS fk_users_api_client;

ALTER TABLE users
    ADD CONSTRAINT fk_users_api_client
    FOREIGN KEY (api_client_id)
    REFERENCES external_api_clients (client_id)
    ON DELETE SET NULL;
