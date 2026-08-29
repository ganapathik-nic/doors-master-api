ALTER TABLE document_service_registry
    ADD COLUMN IF NOT EXISTS payload_mode VARCHAR(20) NOT NULL DEFAULT 'PLAIN_TEXT';

ALTER TABLE document_service_registry
    DROP CONSTRAINT IF EXISTS ck_document_service_payload_mode;

ALTER TABLE document_service_registry
    ADD CONSTRAINT ck_document_service_payload_mode
        CHECK (payload_mode IN ('AES', 'PLAIN_TEXT'));
