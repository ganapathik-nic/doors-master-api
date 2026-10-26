CREATE TABLE write_contracts (
 id bigserial PRIMARY KEY, service_code varchar(40) NOT NULL CHECK(service_code='agreements'),
 contract_version integer NOT NULL, title varchar(100) NOT NULL, purpose text NOT NULL,
 sample_json text NOT NULL, definition_hash char(64) NOT NULL, definition_json jsonb NOT NULL,
 status varchar(20) NOT NULL CHECK(status IN ('DRAFT','PENDING','APPROVED','REJECTED')),
 proposer text NOT NULL, reviewer text, review_note text, request_id bigint,
 sandbox_agent varchar(255), sandbox_evidence jsonb, tested_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, reviewed_at timestamptz,
 UNIQUE(service_code,contract_version)
);
CREATE TABLE write_deployments (
 id bigserial PRIMARY KEY, contract_id bigint NOT NULL REFERENCES write_contracts,
 instance_id varchar(80) NOT NULL, agent_id varchar(255) NOT NULL REFERENCES agents,
 status varchar(20) NOT NULL DEFAULT 'NOT_PROVISIONED' CHECK(status IN ('NOT_PROVISIONED','VERIFIED')),
 verification jsonb, verified_at timestamptz, created_by text NOT NULL,
 UNIQUE(contract_id,instance_id)
);
CREATE TABLE write_governance_events (
 id bigserial PRIMARY KEY, contract_id bigint REFERENCES write_contracts,
 actor text NOT NULL, action text NOT NULL, detail text, occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE inbound_write_routes ADD COLUMN deployment_id bigint REFERENCES write_deployments;
-- No existing mapping has an approved contract. Legacy mappings fail closed.
UPDATE inbound_write_routes SET enabled=false;
