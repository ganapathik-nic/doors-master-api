INSERT INTO document_download_audit (
    correlation_id, client_name, service_name, query_name, agent_id,
    document_type, download_id, file_name, eligibility_mode, outcome,
    http_status, error_code, error_message, started_at, completed_at, duration_ms,
    client_ip
)
SELECT
    'legacy-' || log_id,
    COALESCE(NULLIF(username, ''), 'LEGACY_UNKNOWN'),
    COALESCE(
        NULLIF(substring(endpoint FROM '/documents/services/([^/]+)/download'), ''),
        'LEGACY_UNKNOWN'
    ),
    NULLIF(query_name, ''),
    NULL,
    'LEGACY',
    NULL,
    NULL,
    'OPEN',
    CASE WHEN status_code BETWEEN 200 AND 299 THEN 'LEGACY_SUCCESS' ELSE 'LEGACY_FAILED' END,
    status_code,
    error_code,
    CASE
        WHEN error_message IS NULL THEN 'Imported from unified audit history; per-file checksum evidence was not captured.'
        ELSE LEFT(regexp_replace(error_message, E'[\r\n\t]+', ' ', 'g'), 500)
    END,
    execution_time - (COALESCE(duration_ms, 0) * INTERVAL '1 millisecond'),
    execution_time,
    COALESCE(duration_ms, 0),
    client_ip
FROM unified_audit_logs
WHERE endpoint ~ '/documents/services/[^/]+/download$'
ON CONFLICT (correlation_id) DO NOTHING;

