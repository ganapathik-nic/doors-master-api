ALTER TABLE unified_audit_logs
    ADD COLUMN IF NOT EXISTS error_code VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_unified_audit_logs_error_code
    ON unified_audit_logs (error_code);
