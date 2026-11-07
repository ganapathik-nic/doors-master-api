CREATE TABLE IF NOT EXISTS configuration_audit_events (
    audit_id            bigserial PRIMARY KEY,
    occurred_at         timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_username      varchar(255) NOT NULL,
    actor_role          varchar(255),
    action              varchar(160) NOT NULL,
    entity_type         varchar(100) NOT NULL,
    entity_id           varchar(255),
    entity_name         varchar(500),
    old_value           jsonb,
    new_value           jsonb,
    changed_fields      jsonb NOT NULL DEFAULT '[]'::jsonb,
    justification       text,
    client_ip           varchar(128),
    session_id          varchar(255),
    trace_id            varchar(64) NOT NULL,
    endpoint            text NOT NULL,
    http_method         varchar(12) NOT NULL,
    http_status         integer NOT NULL,
    outcome             varchar(30) NOT NULL,
    failure_code        varchar(100),
    failure_message     text
);

CREATE INDEX IF NOT EXISTS idx_configuration_audit_time
    ON configuration_audit_events (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_configuration_audit_actor
    ON configuration_audit_events (actor_username, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_configuration_audit_entity
    ON configuration_audit_events (entity_type, entity_id, occurred_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_configuration_audit_trace
    ON configuration_audit_events (trace_id);

CREATE OR REPLACE FUNCTION prevent_configuration_audit_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'configuration audit records are append-only';
END;
$$;

DROP TRIGGER IF EXISTS configuration_audit_no_update ON configuration_audit_events;
CREATE TRIGGER configuration_audit_no_update
BEFORE UPDATE OR DELETE ON configuration_audit_events
FOR EACH ROW EXECUTE FUNCTION prevent_configuration_audit_mutation();

REVOKE UPDATE, DELETE, TRUNCATE ON configuration_audit_events FROM PUBLIC;
