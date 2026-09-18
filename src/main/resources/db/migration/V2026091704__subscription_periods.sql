-- Preserve the existing accounting entry as the first independent period.
CREATE TABLE api_subscription_periods (
    period_id BIGSERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES api_user_licenses(user_id),
    valid_from DATE NOT NULL,
    valid_to DATE NOT NULL,
    gepnic_due NUMERIC(15,2) NOT NULL DEFAULT 0,
    gepnic_paid NUMERIC(15,2) NOT NULL DEFAULT 0,
    doors_due NUMERIC(15,2) NOT NULL DEFAULT 0,
    doors_paid NUMERIC(15,2) NOT NULL DEFAULT 0,
    notes VARCHAR(2000) NOT NULL DEFAULT '',
    version BIGINT NOT NULL DEFAULT 1,
    updated_by VARCHAR(255) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK(valid_to >= valid_from),
    CHECK(gepnic_due >= 0 AND gepnic_paid >= 0 AND doors_due >= 0 AND doors_paid >= 0),
    UNIQUE(user_id, valid_from, valid_to)
);
CREATE INDEX api_subscription_periods_user_dates_idx ON api_subscription_periods(user_id, valid_from, valid_to);
CREATE TABLE api_subscription_period_history (
    history_id BIGSERIAL PRIMARY KEY,
    period_id BIGINT NOT NULL REFERENCES api_subscription_periods(period_id),
    user_id INTEGER NOT NULL REFERENCES users(user_id),
    version BIGINT NOT NULL,
    snapshot JSONB NOT NULL,
    changed_by VARCHAR(255) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(period_id, version)
);
INSERT INTO api_subscription_periods(user_id,valid_from,valid_to,gepnic_due,gepnic_paid,doors_due,doors_paid,notes,updated_by,updated_at)
SELECT user_id,valid_from,valid_to,gepnic_due,gepnic_paid,doors_due,doors_paid,notes,updated_by,updated_at
FROM api_user_licenses;
INSERT INTO api_subscription_period_history(period_id,user_id,version,snapshot,changed_by,changed_at)
SELECT period_id,user_id,version,to_jsonb(p),updated_by,updated_at FROM api_subscription_periods p;

-- Account mapping and service status now have a separate lifecycle from accounting.
-- Earlier combined snapshots remain intact in api_license_history.
ALTER TABLE api_user_licenses
    DROP COLUMN valid_from, DROP COLUMN valid_to,
    DROP COLUMN gepnic_due, DROP COLUMN gepnic_paid,
    DROP COLUMN doors_due, DROP COLUMN doors_paid, DROP COLUMN notes;
