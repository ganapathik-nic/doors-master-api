ALTER TABLE inbound_write_audit
    ADD COLUMN IF NOT EXISTS client_ip varchar(64);

CREATE INDEX IF NOT EXISTS idx_inbound_write_audit_client_ip
    ON inbound_write_audit (client_ip);
