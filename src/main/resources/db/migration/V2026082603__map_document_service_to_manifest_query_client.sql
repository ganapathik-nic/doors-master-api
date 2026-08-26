ALTER TABLE document_service_registry
    ADD COLUMN IF NOT EXISTS manifest_query_name VARCHAR(100),
    ADD COLUMN IF NOT EXISTS manifest_client_name VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_document_service_manifest_query
    ON document_service_registry(manifest_query_name);

CREATE INDEX IF NOT EXISTS idx_document_service_manifest_client
    ON document_service_registry(manifest_client_name);
