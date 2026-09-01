ALTER TABLE document_download_audit
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_document_download_audit_trace_id
    ON document_download_audit (trace_id);
