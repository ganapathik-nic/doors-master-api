ALTER TABLE document_download_policies
    DROP COLUMN IF EXISTS linked_api_unique_name,
    DROP COLUMN IF EXISTS agent_rules;

DROP INDEX IF EXISTS idx_document_policy_linked_api;

CREATE TABLE IF NOT EXISTS document_service_registry (
    service_id BIGSERIAL PRIMARY KEY,
    agent_id VARCHAR(255) NOT NULL UNIQUE REFERENCES agents(agent_id),
    service_name VARCHAR(200) NOT NULL,
    base_url VARCHAR(1000) NOT NULL,
    download_path VARCHAR(500) NOT NULL DEFAULT '/Documents/downloadDocuments',
    access_mode VARCHAR(30) NOT NULL DEFAULT 'MASTER_DIRECT',
    connect_timeout_ms INTEGER NOT NULL DEFAULT 10000,
    read_timeout_ms INTEGER NOT NULL DEFAULT 120000,
    verify_tls BOOLEAN NOT NULL DEFAULT TRUE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(255) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_document_service_access_mode
        CHECK (access_mode IN ('MASTER_DIRECT', 'AGENT_PROXY')),
    CONSTRAINT ck_document_service_timeouts
        CHECK (connect_timeout_ms BETWEEN 1000 AND 60000
            AND read_timeout_ms BETWEEN 1000 AND 600000)
);

CREATE INDEX IF NOT EXISTS idx_document_service_active
    ON document_service_registry(is_active);
