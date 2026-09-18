CREATE TABLE gateway_client_protocol (
 client_id BIGINT PRIMARY KEY REFERENCES external_api_clients(client_id),
 minimum_version INTEGER NOT NULL CHECK (minimum_version IN (1,2))
);
CREATE TABLE gateway_request_claim (
 client_id BIGINT NOT NULL REFERENCES external_api_clients(client_id),
 request_id UUID NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY (client_id, request_id)
);
CREATE INDEX gateway_request_claim_expiry ON gateway_request_claim(expires_at);
