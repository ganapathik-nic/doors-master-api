CREATE TABLE gateway_protocol_catalog (
 version INTEGER PRIMARY KEY,
 label TEXT NOT NULL,
 handler TEXT NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE
);
INSERT INTO gateway_protocol_catalog VALUES (1,'V1 legacy','LEGACY',TRUE),(2,'V2 signed freshness','SIGNED_FRESHNESS',TRUE);
CREATE TABLE gateway_query_protocol (
 client_id BIGINT NOT NULL REFERENCES external_api_clients(client_id),
 query_id BIGINT NOT NULL REFERENCES sql_templates(query_id),
 version INTEGER NOT NULL REFERENCES gateway_protocol_catalog(version),
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 deprecated BOOLEAN NOT NULL DEFAULT FALSE,
 retire_at TIMESTAMPTZ,
 last_used_at TIMESTAMPTZ,
 PRIMARY KEY(client_id,query_id,version)
);
-- Preserve existing compatibility and any already-enforced client minimum.
INSERT INTO gateway_query_protocol(client_id,query_id,version,enabled,deprecated)
SELECT DISTINCT m.client_id,m.query_id,v.version,
 v.version >= COALESCE(c.minimum_version,1), v.version=1
FROM external_client_query_map m CROSS JOIN gateway_protocol_catalog v
LEFT JOIN gateway_client_protocol c ON c.client_id=m.client_id;
CREATE TABLE gateway_protocol_policy_audit (
 id BIGSERIAL PRIMARY KEY,
 client_id BIGINT NOT NULL,
 query_id BIGINT NOT NULL,
 version INTEGER NOT NULL,
 changed_by TEXT NOT NULL,
 changed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 old_policy JSONB,
 new_policy JSONB NOT NULL
);
