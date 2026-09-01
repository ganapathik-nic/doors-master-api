CREATE TABLE IF NOT EXISTS document_download_audit (
    audit_id BIGSERIAL PRIMARY KEY,
    correlation_id VARCHAR(64) NOT NULL UNIQUE,
    client_name VARCHAR(255) NOT NULL,
    service_name VARCHAR(255) NOT NULL,
    query_name VARCHAR(255),
    agent_id VARCHAR(255),
    document_type VARCHAR(100),
    download_id VARCHAR(255),
    file_name VARCHAR(1000),
    eligibility_mode VARCHAR(30) NOT NULL,
    outcome VARCHAR(40) NOT NULL,
    http_status INTEGER,
    content_length BIGINT,
    source_sha256 VARCHAR(64),
    destination_sha256 VARCHAR(64),
    destination_verified BOOLEAN,
    error_code VARCHAR(100),
    error_message TEXT,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP NOT NULL,
    duration_ms BIGINT NOT NULL,
    receipt_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_document_download_audit_client_time
    ON document_download_audit (client_name, completed_at DESC);
CREATE INDEX IF NOT EXISTS idx_document_download_audit_service_time
    ON document_download_audit (service_name, completed_at DESC);

