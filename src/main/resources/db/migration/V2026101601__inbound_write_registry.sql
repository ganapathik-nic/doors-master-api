CREATE TABLE inbound_write_routes (
 id bigserial PRIMARY KEY,
 service_code varchar(40) NOT NULL,
 instance_id varchar(80) NOT NULL,
 agent_id varchar(255) NOT NULL REFERENCES agents(agent_id),
 client_id bigint NOT NULL REFERENCES external_api_clients(client_id),
 enabled boolean NOT NULL DEFAULT false,
 created_by text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(service_code,instance_id,client_id)
);
CREATE TABLE inbound_write_audit (
 id bigserial PRIMARY KEY, request_id uuid NOT NULL,
 client_id bigint NOT NULL, service_code varchar(40) NOT NULL,
 instance_id varchar(80) NOT NULL, agent_id varchar(255),
 outcome varchar(80) NOT NULL, http_status integer NOT NULL,
 payload_sha256 char(64) NOT NULL,
 occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX inbound_write_audit_request ON inbound_write_audit(client_id,request_id);
