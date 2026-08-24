CREATE TABLE IF NOT EXISTS user_api_clients (
    user_id INTEGER NOT NULL,
    client_id BIGINT NOT NULL,
    assigned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, client_id),
    CONSTRAINT fk_user_api_clients_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE CASCADE,
    CONSTRAINT fk_user_api_clients_client FOREIGN KEY (client_id)
        REFERENCES external_api_clients (client_id) ON DELETE CASCADE
);

INSERT INTO user_api_clients (user_id, client_id)
SELECT user_id, api_client_id
FROM users
WHERE api_client_id IS NOT NULL
ON CONFLICT DO NOTHING;

ALTER TABLE users DROP CONSTRAINT IF EXISTS fk_users_api_client;
DROP INDEX IF EXISTS idx_users_api_client_id;
ALTER TABLE users DROP COLUMN IF EXISTS api_client_id;
