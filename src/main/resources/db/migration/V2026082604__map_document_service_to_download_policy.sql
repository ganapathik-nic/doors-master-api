ALTER TABLE document_service_registry
    ADD COLUMN IF NOT EXISTS document_download_policy_code VARCHAR(100);

ALTER TABLE document_service_registry
    ADD CONSTRAINT fk_document_service_download_policy
    FOREIGN KEY (document_download_policy_code)
    REFERENCES document_download_policies(policy_code);

CREATE UNIQUE INDEX IF NOT EXISTS uq_document_service_name_ci
    ON document_service_registry(LOWER(service_name));
