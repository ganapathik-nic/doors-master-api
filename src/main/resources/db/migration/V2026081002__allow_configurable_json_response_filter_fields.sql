ALTER TABLE external_client_query_map
    DROP CONSTRAINT IF EXISTS chk_client_response_filter_pair;

ALTER TABLE external_client_query_map
    ADD CONSTRAINT chk_client_response_filter_pair CHECK (
        (response_filter_column IS NULL AND response_filter_value IS NULL)
        OR
        (
            response_filter_column ~ '^[A-Za-z_][A-Za-z0-9_]*$'
            AND NULLIF(BTRIM(response_filter_value), '') IS NOT NULL
        )
    );
