ALTER TABLE users
    ALTER COLUMN status TYPE VARCHAR(40),
    ADD COLUMN IF NOT EXISTS mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS mfa_secret_encrypted TEXT,
    ADD COLUMN IF NOT EXISTS vpn_ip VARCHAR(64),
    ADD COLUMN IF NOT EXISTS vpn_certificate_reference VARCHAR(255),
    ADD COLUMN IF NOT EXISTS vpn_status VARCHAR(30) NOT NULL DEFAULT 'NOT_REQUIRED',
    ADD COLUMN IF NOT EXISTS privileged_approved_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS privileged_approved_at TIMESTAMP;

UPDATE users
SET vpn_status = 'PENDING_VPN_CONFIRMATION'
WHERE UPPER(role) IN ('DATAMANAGER', 'SECURITYADMIN')
  AND vpn_status = 'NOT_REQUIRED';

UPDATE users
SET status = 'PENDING_SECURITY_APPROVAL',
    is_active = FALSE,
    current_session_id = NULL
WHERE UPPER(role) = 'DATAMANAGER'
  AND vpn_status <> 'CONFIRMED';

-- Force existing human sessions through MFA enrollment after deployment.
UPDATE users SET current_session_id = NULL WHERE mfa_enabled = FALSE;

