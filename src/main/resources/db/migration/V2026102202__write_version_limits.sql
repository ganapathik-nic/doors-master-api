ALTER TABLE inbound_write_routes ADD COLUMN max_versions bigint CHECK (max_versions BETWEEN 1 AND 1000000);
