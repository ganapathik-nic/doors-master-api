ALTER TABLE document_download_audit
    ADD COLUMN IF NOT EXISTS success_code VARCHAR(100),
    ADD COLUMN IF NOT EXISTS endpoint VARCHAR(2000),
    ADD COLUMN IF NOT EXISTS upstream_endpoint VARCHAR(2000);
