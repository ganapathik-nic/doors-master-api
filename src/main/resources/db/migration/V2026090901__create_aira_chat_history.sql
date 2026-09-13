CREATE TABLE IF NOT EXISTS aira_conversations (
    conversation_id BIGSERIAL PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    title VARCHAR(160) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_aira_conversations_user_updated
    ON aira_conversations (username, updated_at DESC);

CREATE TABLE IF NOT EXISTS aira_chat_exchanges (
    exchange_id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES aira_conversations(conversation_id) ON DELETE CASCADE,
    prompt TEXT NOT NULL,
    answer TEXT NOT NULL,
    answer_type VARCHAR(40),
    knowledge_snippets_matched INTEGER NOT NULL DEFAULT 0,
    refined BOOLEAN NOT NULL DEFAULT FALSE,
    captured_at VARCHAR(80),
    execution_data JSONB,
    rating SMALLINT,
    rating_comment VARCHAR(500),
    rated_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_aira_exchange_rating CHECK (rating IS NULL OR rating BETWEEN 1 AND 5)
);

CREATE INDEX IF NOT EXISTS idx_aira_exchanges_conversation_time
    ON aira_chat_exchanges (conversation_id, created_at);
CREATE INDEX IF NOT EXISTS idx_aira_exchanges_rating
    ON aira_chat_exchanges (rating) WHERE rating IS NOT NULL;
