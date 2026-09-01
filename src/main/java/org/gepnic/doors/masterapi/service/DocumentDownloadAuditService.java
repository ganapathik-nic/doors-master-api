package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DocumentDownloadAuditService {
    private final JdbcTemplate jdbcTemplate;

    public void record(String correlationId, String clientName, String serviceName,
                       String queryName, String agentId, String policyCode, String clientIp,
                       Map<String, Object> document,
                       Instant startedAt, String outcome, int httpStatus, Long contentLength,
                       String sourceSha256, Integer queryResponseCode,
                       Integer documentServiceResponseCode, String errorCode, String errorMessage,
                       String traceId, String successCode, String endpoint, String upstreamEndpoint) {
        Instant completedAt = Instant.now();
        jdbcTemplate.update("""
                INSERT INTO document_download_audit
                (correlation_id, client_name, service_name, query_name, agent_id,
                 document_type, download_id, file_name, eligibility_mode, outcome,
                 http_status, content_length, source_sha256, error_code, error_message,
                 started_at, completed_at, duration_ms, policy_code, policy_version, client_ip,
                 query_response_code, document_service_response_code, trace_id)
                 
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        (SELECT policy_version FROM document_download_policies WHERE policy_code = ?), ?, ?, ?, ?)
                ON CONFLICT (correlation_id) DO NOTHING
                """,
                correlationId, clientName, serviceName, queryName, agentId,
                first(document.get("documentType"), document.get("docCode")), text(document.get("downloadId")),
                text(document.get("fileName")), outcome, httpStatus, contentLength,
                sourceSha256, errorCode, sanitize(errorMessage), Timestamp.from(startedAt),
                Timestamp.from(completedAt), Math.max(0, completedAt.toEpochMilli() - startedAt.toEpochMilli()),
                policyCode, policyCode, clientIp, queryResponseCode, documentServiceResponseCode, traceId);
        jdbcTemplate.update("""
                UPDATE document_download_audit
                   SET success_code = ?, endpoint = ?, upstream_endpoint = ?
                 WHERE correlation_id = ?
                """, successCode, sanitizeEndpoint(endpoint), sanitizeEndpoint(upstreamEndpoint), correlationId);
    }

    public void recordReceipt(String correlationId, String clientName,
                              String sourceSha256, String destinationSha256, boolean verified) {
        int updated = jdbcTemplate.update("""
                UPDATE document_download_audit
                   SET destination_sha256 = ?, destination_verified = ?, receipt_at = CURRENT_TIMESTAMP,
                       outcome = CASE WHEN ? THEN outcome ELSE 'CHECKSUM_MISMATCH' END
                 WHERE correlation_id = ? AND client_name = ? AND source_sha256 = ?
                """, destinationSha256, verified, verified, correlationId, clientName, sourceSha256);
        if (updated != 1) throw new IllegalArgumentException("Unknown or mismatched document download receipt");
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String first(Object preferred, Object fallback) {
        String value = text(preferred);
        return value == null || value.isBlank() ? text(fallback) : value;
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return clean.length() <= 500 ? clean : clean.substring(0, 500);
    }

    private static String sanitizeEndpoint(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.replaceAll("[\\r\\n\\t]+", "").trim();
        return clean.length() <= 2000 ? clean : clean.substring(0, 2000);
    }
}
