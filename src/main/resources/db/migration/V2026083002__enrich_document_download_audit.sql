ALTER TABLE document_download_audit
    ADD COLUMN IF NOT EXISTS policy_code VARCHAR(255),
    ADD COLUMN IF NOT EXISTS policy_version INTEGER,
    ADD COLUMN IF NOT EXISTS client_ip VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_document_download_audit_outcome_time
    ON document_download_audit (outcome, completed_at DESC);
CREATE INDEX IF NOT EXISTS idx_document_download_audit_correlation
    ON document_download_audit (correlation_id);

