package org.gepnic.doors.masterapi.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.gepnic.doors.masterapi.model.Agent;
import org.gepnic.doors.masterapi.model.SqlTemplate;
import org.gepnic.doors.masterapi.repository.AgentRepository;
import org.gepnic.doors.masterapi.repository.SqlTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Comparator;
import java.util.List;

@Service
public class AiraContextService {
    private final AgentRepository agentRepository;
    private final SqlTemplateRepository sqlTemplateRepository;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public AiraContextService(AgentRepository agentRepository, SqlTemplateRepository sqlTemplateRepository,
                              JdbcTemplate jdbcTemplate) {
        this.agentRepository = agentRepository;
        this.sqlTemplateRepository = sqlTemplateRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Kept for isolated tests and deployments that only expose registry metrics. */
    AiraContextService(AgentRepository agentRepository, SqlTemplateRepository sqlTemplateRepository) {
        this(agentRepository, sqlTemplateRepository, null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> buildOperationalSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("capturedAt", OffsetDateTime.now().toString());
        long totalQueries = sqlTemplateRepository.count();
        long activeQueries = sqlTemplateRepository.countByIsActive(true);
        snapshot.put("queries", Map.of(
                "total", totalQueries,
                "pending", sqlTemplateRepository.countByStatus("PENDING"),
                "approved", sqlTemplateRepository.countByStatus("APPROVED"),
                "rejected", sqlTemplateRepository.countByStatus("REJECTED"),
                "active", activeQueries, "inactive", Math.max(0, totalQueries - activeQueries),
                "activeApproved", sqlTemplateRepository.findByStatusAndIsActive("APPROVED", true).size()));
        long activeAgents = agentRepository.countByIsActiveTrue();
        long totalAgents = agentRepository.count();
        snapshot.put("agents", Map.of("total", totalAgents, "active", activeAgents,
                "inactive", Math.max(0, totalAgents - activeAgents),
                "sandbox", agentRepository.findByIsActiveTrueAndIsSandbox(true).size(),
                "production", agentRepository.findActiveProductionAgents().size()));
        snapshot.put("users", Map.of("total", count("SELECT COUNT(*) FROM users"),
                "active", count("SELECT COUNT(*) FROM users WHERE status = 'ACTIVE'"),
                "pending", count("SELECT COUNT(*) FROM users WHERE status = 'PENDING'"),
                "rejected", count("SELECT COUNT(*) FROM users WHERE status = 'REJECTED'")));
        snapshot.put("dataRequests", Map.of("total", count("SELECT COUNT(*) FROM data_pull_requests"),
                "pending", count("SELECT COUNT(*) FROM data_pull_requests WHERE status IN ('PENDING','SUBMITTED')"),
                "approved", count("SELECT COUNT(*) FROM data_pull_requests WHERE status = 'APPROVED'"),
                "rejected", count("SELECT COUNT(*) FROM data_pull_requests WHERE status = 'REJECTED'"),
                "completed", count("SELECT COUNT(*) FROM data_pull_requests WHERE status = 'COMPLETED'"),
                "failed", count("SELECT COUNT(*) FROM data_pull_requests WHERE status = 'FAILED'")));
        snapshot.put("apiClients", Map.of("total", count("SELECT COUNT(*) FROM external_api_clients"),
                "active", count("SELECT COUNT(*) FROM external_api_clients WHERE is_active = true"),
                "inactive", count("SELECT COUNT(*) FROM external_api_clients WHERE is_active = false")));
        snapshot.put("executions", executionMetrics());
        snapshot.put("documentDownloads", documentDownloadMetrics());
        return snapshot;
    }

    private Map<String, Object> documentDownloadMetrics() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("today", documentDownloadPeriod("CURRENT_DATE"));
        values.put("sevenDays", documentDownloadPeriod("CURRENT_TIMESTAMP - INTERVAL '7 days'"));
        values.put("thirtyDays", documentDownloadPeriod("CURRENT_TIMESTAMP - INTERVAL '30 days'"));
        return values;
    }

    private Map<String, Long> documentDownloadPeriod(String since) {
        String base = " FROM document_download_audit WHERE completed_at >= " + since;
        return Map.of(
                "total", count("SELECT COUNT(*)" + base),
                "verified", count("SELECT COUNT(*)" + base + " AND destination_verified = TRUE"),
                "failed", count("SELECT COUNT(*)" + base + " AND outcome IN ('MANIFEST_FAILED','FAILED','STREAM_FAILED')"),
                "mismatched", count("SELECT COUNT(*)" + base + " AND outcome = 'CHECKSUM_MISMATCH'"),
                "pending", count("SELECT COUNT(*)" + base + " AND outcome = 'STREAMED' AND destination_verified IS NULL"),
                "bytes", count("SELECT COALESCE(SUM(content_length), 0)" + base));
    }

    private Map<String, Object> executionMetrics() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("today", executionPeriod("CURRENT_DATE"));
        values.put("sevenDays", executionPeriod("CURRENT_TIMESTAMP - INTERVAL '7 days'"));
        values.put("thirtyDays", executionPeriod("CURRENT_TIMESTAMP - INTERVAL '30 days'"));
        return values;
    }

    private Map<String, Long> executionPeriod(String since) {
        String base = " FROM unified_audit_logs WHERE event_type = 'QUERY_EXECUTION' AND execution_time >= " + since;
        long total = count("SELECT COUNT(*)" + base);
        long failed = count("SELECT COUNT(*)" + base + " AND status_code >= 400");
        return Map.of("total", total, "successful", Math.max(0, total - failed), "failed", failed);
    }

    private long count(String sql) {
        if (jdbcTemplate == null) return 0;
        try {
            Long value = jdbcTemplate.queryForObject(sql, Long.class);
            return value == null ? 0 : value;
        } catch (RuntimeException unavailableMetric) {
            return 0;
        }
    }

    /** A deliberately small, read-only context. SQL text, credentials and row data are never exposed. */
    @Transactional(readOnly = true)
    public String buildOperationalContext() {
        List<Agent> agents = agentRepository.findByIsActiveTrue();
        List<SqlTemplate> reports = sqlTemplateRepository.findByStatusAndIsActive("APPROVED", true);

        StringBuilder context = new StringBuilder("DOORS OPERATIONAL SNAPSHOT\n");
        context.append("Active execution agents: ").append(agents.size()).append('\n');
        agents.stream()
                .sorted(Comparator.comparing(Agent::getAgentId, Comparator.nullsLast(String::compareToIgnoreCase)))
                .limit(50)
                .forEach(agent -> context.append("- Agent: ")
                        .append(clean(agent.getAgentId())).append(" | ")
                        .append(clean(agent.getDisplayName())).append(" | type=")
                        .append(clean(agent.getAgentType())).append(" | sandbox=")
                        .append(Boolean.TRUE.equals(agent.getIsSandbox())).append('\n'));

        context.append("Approved report catalogue: ").append(reports.size()).append('\n');
        reports.stream()
                .sorted(Comparator.comparing(SqlTemplate::getUniqueName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .limit(100)
                .forEach(report -> context.append("- Report: ")
                        .append(clean(report.getUniqueName())).append(" | category=")
                        .append(clean(report.getCategory())).append(" | subcategory=")
                        .append(clean(report.getSubcategory())).append(" | defaultAgent=")
                        .append(clean(report.getDefaultAgentId())).append(" | parameters=")
                        .append(report.getParameters().stream().map(AiraContextService::clean).toList())
                        .append(" | description=").append(clean(report.getDescription())).append('\n'));
        return context.toString();
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) return "not specified";
        return value.replaceAll("[\\r\\n\\t]+", " ").trim();
    }
}
