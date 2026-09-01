ALTER TABLE document_download_audit
    ADD COLUMN IF NOT EXISTS query_response_code INTEGER,
    ADD COLUMN IF NOT EXISTS document_service_response_code INTEGER;
