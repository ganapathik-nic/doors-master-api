package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DocumentDownloadReportingService {
    private final JdbcTemplate jdbcTemplate;

    public Map<String, Object> logs(int page, int size, String clientName, String serviceName,
                                    String agentId, String outcome, String verification,
                                    String search, LocalDate startDate, LocalDate endDate) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(200, Math.max(10, size));
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        contains(where, params, "client_name", clientName);
        contains(where, params, "service_name", serviceName);
        contains(where, params, "agent_id", agentId);
        equals(where, params, "outcome", outcome);
        if ("VERIFIED".equalsIgnoreCase(verification)) where.append(" AND destination_verified = TRUE");
        else if ("MISMATCH".equalsIgnoreCase(verification)) where.append(" AND destination_verified = FALSE");
        else if ("PENDING".equalsIgnoreCase(verification)) where.append(" AND destination_verified IS NULL AND outcome = 'STREAMED'");
        if (search != null && !search.isBlank()) {
            where.append(" AND (correlation_id ILIKE ? OR file_name ILIKE ? OR download_id ILIKE ? OR query_name ILIKE ? OR client_ip ILIKE ? OR error_code ILIKE ? OR trace_id ILIKE ?)");
            String value = "%" + search.trim() + "%";
            params.add(value); params.add(value); params.add(value); params.add(value); params.add(value);
            params.add(value); params.add(value);
        }
        if (startDate != null) { where.append(" AND completed_at >= ?"); params.add(Timestamp.valueOf(startDate.atStartOfDay())); }
        if (endDate != null) { where.append(" AND completed_at < ?"); params.add(Timestamp.valueOf(endDate.plusDays(1).atStartOfDay())); }

        Map<String, Object> aggregate = jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS count, COALESCE(SUM(content_length), 0) AS bytes FROM document_download_audit" + where,
                params.toArray());
        long total = ((Number) aggregate.getOrDefault("count", 0)).longValue();
        long totalBytes = ((Number) aggregate.getOrDefault("bytes", 0)).longValue();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(safeSize); pageParams.add((long) safePage * safeSize);
        List<Map<String, Object>> content = jdbcTemplate.queryForList("""
                SELECT audit_id AS "auditId", correlation_id AS "correlationId",
                       client_name AS "clientName", service_name AS "serviceName",
                       query_name AS "queryName", agent_id AS "agentId",
                       policy_code AS "policyCode", policy_version AS "policyVersion",
                       document_type AS "documentType", download_id AS "downloadId",
                       file_name AS "fileName", eligibility_mode AS "eligibilityMode",
                       outcome, http_status AS "httpStatus", content_length AS "contentLength",
                       source_sha256 AS "sourceSha256", destination_sha256 AS "destinationSha256",
                       destination_verified AS "destinationVerified", client_ip AS "clientIp",
                       query_response_code AS "queryResponseCode",
                       document_service_response_code AS "documentServiceResponseCode",
                       error_code AS "errorCode", error_message AS "errorMessage",
                       trace_id AS "traceId",
                       success_code AS "successCode", endpoint AS "endpoint",
                       upstream_endpoint AS "upstreamEndpoint",
                       started_at AS "startedAt", completed_at AS "completedAt",
                       duration_ms AS "durationMs", receipt_at AS "receiptAt"
                  FROM document_download_audit
                """ + where + " ORDER BY completed_at DESC LIMIT ? OFFSET ?", pageParams.toArray());
        return Map.of("content", content, "page", safePage, "size", safeSize,
                "totalElements", total, "totalContentLength", totalBytes,
                "totalPages", (total + safeSize - 1) / safeSize);
    }

    public Map<String, Object> dashboard() {
        Map<String, Object> totals = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) FILTER (WHERE completed_at >= CURRENT_DATE) AS "downloadsToday",
                       COUNT(*) FILTER (WHERE completed_at >= CURRENT_DATE AND destination_verified = TRUE) AS "verifiedToday",
                       COUNT(*) FILTER (WHERE completed_at >= CURRENT_DATE AND outcome IN ('MANIFEST_FAILED','FAILED','STREAM_FAILED')) AS "failedToday",
                       COUNT(*) FILTER (WHERE completed_at >= CURRENT_DATE AND outcome = 'CHECKSUM_MISMATCH') AS "mismatchesToday",
                       COUNT(*) FILTER (WHERE completed_at >= CURRENT_DATE AND outcome = 'STREAMED' AND destination_verified IS NULL) AS "pendingReceiptsToday",
                       COALESCE(SUM(content_length) FILTER (WHERE completed_at >= CURRENT_DATE), 0) AS "bytesToday",
                       COALESCE(ROUND(AVG(duration_ms) FILTER (WHERE completed_at >= CURRENT_DATE)), 0) AS "averageDurationMs",
                       COUNT(*) FILTER (WHERE destination_verified = FALSE OR outcome = 'CHECKSUM_MISMATCH') AS "totalMismatches"
                  FROM document_download_audit
                """);
        Integer activeServices = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_service_registry WHERE is_active = TRUE", Integer.class);
        Integer activePolicies = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_download_policies WHERE status = 'ACTIVE'", Integer.class);
        totals.put("activeServices", activeServices == null ? 0 : activeServices);
        totals.put("activePolicies", activePolicies == null ? 0 : activePolicies);

        List<Map<String, Object>> trend = jdbcTemplate.queryForList("""
                SELECT day::date AS day,
                       COUNT(a.audit_id) AS total,
                       COUNT(a.audit_id) FILTER (WHERE a.destination_verified = TRUE) AS verified,
                       COUNT(a.audit_id) FILTER (WHERE a.outcome IN ('MANIFEST_FAILED','FAILED','STREAM_FAILED','CHECKSUM_MISMATCH')) AS failed
                  FROM generate_series(CURRENT_DATE - INTERVAL '6 days', CURRENT_DATE, INTERVAL '1 day') day
                  LEFT JOIN document_download_audit a ON a.completed_at >= day AND a.completed_at < day + INTERVAL '1 day'
                 GROUP BY day ORDER BY day
                """);
        List<Map<String, Object>> byService = jdbcTemplate.queryForList("""
                SELECT service_name AS name, COUNT(*) AS count
                  FROM document_download_audit
                 WHERE completed_at >= CURRENT_DATE - INTERVAL '30 days'
                 GROUP BY service_name ORDER BY count DESC LIMIT 10
                """);
        List<Map<String, Object>> recentFailures = jdbcTemplate.queryForList("""
                SELECT correlation_id AS "correlationId", completed_at AS "completedAt",
                       service_name AS "serviceName", file_name AS "fileName", outcome,
                       error_code AS "errorCode", error_message AS "errorMessage"
                  FROM document_download_audit
                 WHERE outcome IN ('MANIFEST_FAILED','FAILED','STREAM_FAILED','CHECKSUM_MISMATCH')
                 ORDER BY completed_at DESC LIMIT 8
                """);
        Map<String, Object> baseline = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS attempts,
                       COUNT(*) FILTER (WHERE outcome IN ('MANIFEST_FAILED','FAILED','STREAM_FAILED')) AS failures
                  FROM document_download_audit
                 WHERE completed_at >= CURRENT_DATE - INTERVAL '7 days' AND completed_at < CURRENT_DATE
                """);
        Map<String, Object> dominantFailure = firstOrEmpty(jdbcTemplate.queryForList("""
                SELECT COALESCE(error_code, outcome) AS code, COUNT(*) AS count
                  FROM document_download_audit
                 WHERE completed_at >= CURRENT_DATE
                   AND outcome IN ('MANIFEST_FAILED','FAILED','STREAM_FAILED','CHECKSUM_MISMATCH')
                 GROUP BY COALESCE(error_code, outcome) ORDER BY count DESC, code LIMIT 1
                """));
        Map<String, Object> dominantFailedService = firstOrEmpty(jdbcTemplate.queryForList("""
                SELECT service_name AS name, COUNT(*) AS count
                  FROM document_download_audit
                 WHERE completed_at >= CURRENT_DATE
                   AND outcome IN ('MANIFEST_FAILED','FAILED','STREAM_FAILED','CHECKSUM_MISMATCH')
                 GROUP BY service_name ORDER BY count DESC, service_name LIMIT 1
                """));
        Integer agedPendingReceipts = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM document_download_audit
                 WHERE outcome = 'STREAMED' AND destination_verified IS NULL
                   AND completed_at < CURRENT_TIMESTAMP - INTERVAL '30 minutes'
                """, Integer.class);
        List<String> recentOutcomes = jdbcTemplate.queryForList(
                "SELECT outcome FROM document_download_audit ORDER BY completed_at DESC LIMIT 20", String.class);
        int recentSuccessStreak = 0;
        for (String value : recentOutcomes) {
            if (!"STREAMED".equals(value) && !"LEGACY_SUCCESS".equals(value)) break;
            recentSuccessStreak++;
        }
        java.sql.Timestamp lastActivity = jdbcTemplate.queryForObject(
                "SELECT MAX(completed_at) FROM document_download_audit", java.sql.Timestamp.class);
        Integer activeInstances = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agents WHERE is_active = TRUE", Integer.class);
        Map<String, Object> leadingInstance = firstOrEmpty(jdbcTemplate.queryForList("""
                SELECT COALESCE(a.display_name, d.agent_id) AS name, d.agent_id AS "agentId", COUNT(*) AS deliveries
                  FROM document_download_audit d
                  LEFT JOIN agents a ON a.agent_id = d.agent_id
                 WHERE d.completed_at >= CURRENT_DATE - INTERVAL '30 days'
                   AND d.outcome = 'STREAMED'
                 GROUP BY COALESCE(a.display_name, d.agent_id), d.agent_id
                 ORDER BY deliveries DESC, name LIMIT 1
                """));
        long todayAttempts = number(totals.get("downloadsToday"));
        long todayFailures = number(totals.get("failedToday"));
        double todayFailureRate = rate(todayFailures, todayAttempts);
        double baselineFailureRate = rate(number(baseline.get("failures")), number(baseline.get("attempts")));
        String health = number(totals.get("mismatchesToday")) > 0 || todayFailureRate >= 20 ? "CRITICAL"
                : todayFailures > 0 || number(agedPendingReceipts) > 0 ? "ATTENTION" : "HEALTHY";
        Map<String, Object> insights = new LinkedHashMap<>();
        insights.put("health", health); insights.put("todayFailureRate", todayFailureRate);
        insights.put("baselineFailureRate", baselineFailureRate); insights.put("dominantFailure", dominantFailure);
        insights.put("dominantFailedService", dominantFailedService);
        insights.put("agedPendingReceipts", agedPendingReceipts == null ? 0 : agedPendingReceipts);
        insights.put("recentSuccessStreak", recentSuccessStreak); insights.put("lastActivityAt", lastActivity);
        insights.put("activeInstances", activeInstances == null ? 0 : activeInstances);
        insights.put("leadingInstance", leadingInstance);
        Map<String, Object> result = new LinkedHashMap<>(totals);
        result.put("trend", trend); result.put("byService", byService); result.put("recentFailures", recentFailures);
        result.put("insights", insights);
        return result;
    }

    public Map<String, Object> filterOptions() {
        List<String> clients = jdbcTemplate.queryForList("""
                SELECT DISTINCT client_name
                  FROM document_download_audit
                 WHERE client_name IS NOT NULL AND client_name <> ''
                 ORDER BY client_name
                """, String.class);
        List<String> services = jdbcTemplate.queryForList("""
                SELECT DISTINCT service_name
                  FROM document_download_audit
                 WHERE service_name IS NOT NULL AND service_name <> ''
                 ORDER BY service_name
                """, String.class);
        return Map.of("clients", clients, "services", services);
    }

    private static void contains(StringBuilder where, List<Object> params, String column, String value) {
        if (value != null && !value.isBlank()) { where.append(" AND ").append(column).append(" ILIKE ?"); params.add("%" + value.trim() + "%"); }
    }
    private static void equals(StringBuilder where, List<Object> params, String column, String value) {
        if (value != null && !value.isBlank()) { where.append(" AND ").append(column).append(" = ?"); params.add(value.trim().toUpperCase()); }
    }
    private static Map<String, Object> firstOrEmpty(List<Map<String, Object>> values) { return values.isEmpty() ? Map.of() : values.get(0); }
    private static long number(Object value) { return value instanceof Number number ? number.longValue() : 0L; }
    private static double rate(long numerator, long denominator) { return denominator == 0 ? 0 : Math.round(numerator * 1000.0 / denominator) / 10.0; }
}
