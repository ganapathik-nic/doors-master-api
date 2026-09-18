-- A licence belongs to a machine client; portal subscribers retain user_api_clients ownership.
CREATE TABLE api_client_licenses (
    client_id BIGINT PRIMARY KEY REFERENCES external_api_clients(client_id),
    agent_id VARCHAR(255) NOT NULL REFERENCES agents(agent_id),
    valid_from DATE NOT NULL,
    valid_to DATE NOT NULL,
    time_zone VARCHAR(100) NOT NULL DEFAULT 'Asia/Kolkata',
    access_from TIME,
    access_to TIME,
    service_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    gepnic_due NUMERIC(15,2) NOT NULL DEFAULT 0,
    gepnic_paid NUMERIC(15,2) NOT NULL DEFAULT 0,
    doors_due NUMERIC(15,2) NOT NULL DEFAULT 0,
    doors_paid NUMERIC(15,2) NOT NULL DEFAULT 0,
    notes VARCHAR(2000) NOT NULL DEFAULT '',
    version BIGINT NOT NULL DEFAULT 1,
    updated_by VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (valid_to >= valid_from),
    CHECK ((access_from IS NULL AND access_to IS NULL) OR
           (access_from IS NOT NULL AND access_to IS NOT NULL AND access_from <> access_to)),
    CHECK (gepnic_due >= 0 AND gepnic_paid >= 0 AND doors_due >= 0 AND doors_paid >= 0)
);

CREATE TABLE api_license_history (
    history_id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL REFERENCES external_api_clients(client_id),
    version BIGINT NOT NULL,
    snapshot JSONB NOT NULL,
    changed_by VARCHAR(255) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(client_id, version)
);

CREATE TABLE api_subscriber_notices (
    notice_id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL REFERENCES external_api_clients(client_id),
    title VARCHAR(160) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    severity VARCHAR(20) NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX api_subscriber_notices_client_idx ON api_subscriber_notices(client_id, expires_at);
CREATE TABLE api_subscriber_notice_reads (
    notice_id BIGINT NOT NULL REFERENCES api_subscriber_notices(notice_id) ON DELETE CASCADE,
    user_id INTEGER NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    read_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(notice_id, user_id)
);

-- Actual application response-body bytes (before reverse-proxy compression), never payload contents.
CREATE TABLE api_egress_events (
    event_id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL REFERENCES external_api_clients(client_id),
    endpoint VARCHAR(2048) NOT NULL,
    status_code INTEGER NOT NULL,
    response_bytes BIGINT NOT NULL CHECK (response_bytes >= 0),
    record_count BIGINT NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL,
    transfer_complete BOOLEAN NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX api_egress_events_client_time_idx ON api_egress_events(client_id, occurred_at);
CREATE INDEX api_egress_events_time_idx ON api_egress_events(occurred_at);

CREATE TABLE api_usage_metadata (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK(singleton),
    tracking_started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO api_usage_metadata(singleton) VALUES(TRUE);
