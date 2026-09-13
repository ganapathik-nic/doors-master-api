ALTER TABLE document_access_grants ADD COLUMN eligibility_json JSONB NOT NULL DEFAULT '{}'::jsonb;
