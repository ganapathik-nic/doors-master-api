-- Replace the unused initial client-based licensing draft with user-based licensing.
-- Never discard configured licences or their audit history: these require explicit reconciliation.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM api_client_licenses) OR EXISTS (SELECT 1 FROM api_license_history) THEN
        RAISE EXCEPTION 'Client-based licensing records exist; reconcile them to API users before applying migration 2026091702';
    END IF;
END $$;

DROP TABLE api_license_history;
DROP TABLE api_client_licenses;

-- One licence and one licensed GePNIC instance per API user.
CREATE TABLE api_user_licenses (
    user_id INTEGER PRIMARY KEY REFERENCES users(user_id),
    agent_id VARCHAR(255) NOT NULL REFERENCES agents(agent_id),
    valid_from DATE NOT NULL,
    valid_to DATE NOT NULL,
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
    CHECK (gepnic_due >= 0 AND gepnic_paid >= 0 AND doors_due >= 0 AND doors_paid >= 0)
);

CREATE TABLE api_license_history (
    history_id BIGSERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(user_id),
    version BIGINT NOT NULL,
    snapshot JSONB NOT NULL,
    changed_by VARCHAR(255) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, version)
);

CREATE INDEX api_user_licenses_agent_idx ON api_user_licenses(agent_id);
CREATE TABLE api_client_schedules (
    client_id BIGINT PRIMARY KEY REFERENCES external_api_clients(client_id),
    version BIGINT NOT NULL,
    windows JSONB NOT NULL DEFAULT '[]',
    updated_by VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE api_client_schedule_history (
    history_id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL REFERENCES external_api_clients(client_id),
    version BIGINT NOT NULL,
    windows JSONB NOT NULL,
    changed_by VARCHAR(255) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(client_id, version)
);

