CREATE TABLE IF NOT EXISTS document_download_policies (
    policy_id BIGSERIAL PRIMARY KEY,
    policy_code VARCHAR(100) NOT NULL UNIQUE,
    policy_name VARCHAR(200) NOT NULL,
    description TEXT,
    linked_api_unique_name VARCHAR(200),
    execution_mode VARCHAR(20) NOT NULL,
    function_name VARCHAR(300),
    eligibility_sql TEXT,
    accepted_identifiers JSONB NOT NULL DEFAULT '[]'::jsonb,
    decision_column VARCHAR(100) NOT NULL DEFAULT 'decision',
    allowed_value VARCHAR(100) NOT NULL DEFAULT 'ALLOW',
    agent_rules JSONB NOT NULL DEFAULT '[]'::jsonb,
    document_types JSONB NOT NULL DEFAULT '[]'::jsonb,
    authorized_client_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    policy_version INTEGER NOT NULL DEFAULT 1,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(255) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_document_policy_execution_mode
        CHECK (execution_mode IN ('FUNCTION', 'QUERY')),
    CONSTRAINT ck_document_policy_status
        CHECK (status IN ('DRAFT', 'TESTED', 'ACTIVE', 'INACTIVE', 'RETIRED')),
    CONSTRAINT ck_document_policy_execution_source
        CHECK ((execution_mode = 'FUNCTION' AND function_name IS NOT NULL AND eligibility_sql IS NULL)
            OR (execution_mode = 'QUERY' AND eligibility_sql IS NOT NULL AND function_name IS NULL))
);

CREATE INDEX IF NOT EXISTS idx_document_policy_status
    ON document_download_policies(status);
CREATE INDEX IF NOT EXISTS idx_document_policy_linked_api
    ON document_download_policies(linked_api_unique_name);
