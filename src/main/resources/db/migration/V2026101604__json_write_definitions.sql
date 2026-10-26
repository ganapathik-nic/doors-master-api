ALTER TABLE write_contracts DROP CONSTRAINT write_contracts_service_code_check;
ALTER TABLE write_contracts ADD CONSTRAINT write_contracts_service_code_check CHECK(service_code ~ '^[a-z][a-z-]{0,39}$');
