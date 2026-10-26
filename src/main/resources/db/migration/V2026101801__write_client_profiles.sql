CREATE TABLE write_client_profiles (
 client_id bigint PRIMARY KEY REFERENCES external_api_clients(client_id),
 environment varchar(16) NOT NULL CHECK (environment IN ('DEVELOPMENT','UAT','PRODUCTION','UNSPECIFIED')),
 application_name varchar(120) NOT NULL,
 created_by text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- Preserve existing identities and mappings without granting any new access.
INSERT INTO write_client_profiles(client_id,environment,application_name,created_by)
SELECT DISTINCT c.client_id,'UNSPECIFIED',left(c.client_name,120),'MIGRATION'
FROM external_api_clients c JOIN inbound_write_routes r ON r.client_id=c.client_id;
ALTER TABLE inbound_write_routes ADD COLUMN protocol_version integer NOT NULL DEFAULT 1 CHECK(protocol_version=1);
