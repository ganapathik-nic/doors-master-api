ALTER TABLE write_contracts DROP CONSTRAINT write_contracts_status_check;
ALTER TABLE write_contracts ADD CONSTRAINT write_contracts_status_check
 CHECK(status IN ('DRAFT','PENDING','APPROVED','DISABLED','REJECTED'));
ALTER TABLE write_contracts ADD COLUMN category varchar(80) NOT NULL DEFAULT 'INWARD';
ALTER TABLE write_contracts ADD COLUMN subcategory varchar(80) NOT NULL DEFAULT 'AGREEMENTS';
