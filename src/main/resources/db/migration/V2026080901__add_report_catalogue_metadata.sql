ALTER TABLE sql_templates
    ADD COLUMN IF NOT EXISTS subcategory VARCHAR(100),
    ADD COLUMN IF NOT EXISTS default_agent_id VARCHAR(100);
