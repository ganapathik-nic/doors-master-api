ALTER TABLE external_client_query_map
    ADD COLUMN IF NOT EXISTS response_filter_column VARCHAR(100),
    ADD COLUMN IF NOT EXISTS response_filter_value VARCHAR(500);

ALTER TABLE external_client_query_map
    DROP CONSTRAINT IF EXISTS chk_client_response_filter_pair;

ALTER TABLE external_client_query_map
    ADD CONSTRAINT chk_client_response_filter_pair CHECK (
        (response_filter_column IS NULL AND response_filter_value IS NULL)
        OR
        (response_filter_column = 't_ORGID'
            AND NULLIF(BTRIM(response_filter_value), '') IS NOT NULL)
    );
