ALTER TABLE document_service_registry
    DROP CONSTRAINT IF EXISTS document_service_registry_agent_id_key;

CREATE INDEX IF NOT EXISTS idx_document_service_agent_id
    ON document_service_registry(agent_id);
