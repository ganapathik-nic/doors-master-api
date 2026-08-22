CREATE TABLE IF NOT EXISTS template_contracts (
    contract_id BIGSERIAL PRIMARY KEY,
    template_id BIGINT NOT NULL REFERENCES sql_templates(query_id),
    contract_version INTEGER NOT NULL,
    request_schema JSONB NOT NULL,
    response_schema JSONB,
    contract_status VARCHAR(30) NOT NULL,
    discovery_source VARCHAR(30) NOT NULL,
    schema_hash VARCHAR(64),
    change_type VARCHAR(30),
    observation_count BIGINT NOT NULL DEFAULT 0,
    first_observed_at TIMESTAMP,
    last_observed_at TIMESTAMP,
    submitted_at TIMESTAMP,
    submitted_by VARCHAR(100),
    reviewed_at TIMESTAMP,
    reviewed_by VARCHAR(100),
    review_comment TEXT,
    published_at TIMESTAMP,
    is_current BOOLEAN NOT NULL DEFAULT FALSE,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(255) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_template_contract_version UNIQUE (template_id, contract_version)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_template_contract_current
    ON template_contracts(template_id) WHERE is_current = TRUE;
CREATE INDEX IF NOT EXISTS idx_template_contract_status
    ON template_contracts(contract_status);
CREATE INDEX IF NOT EXISTS idx_template_contract_hash
    ON template_contracts(template_id, schema_hash);
