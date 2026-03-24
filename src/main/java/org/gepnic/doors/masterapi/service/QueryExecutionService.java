package org.gepnic.doors.masterapi.service;

import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.client.AgentClient;
import org.gepnic.doors.masterapi.model.Agent;
import org.gepnic.doors.masterapi.model.SqlTemplate;
import org.gepnic.doors.masterapi.model.UnifiedAuditLog;
import org.gepnic.doors.masterapi.repository.AgentRepository;
import org.gepnic.doors.masterapi.repository.SqlTemplateRepository;
import org.gepnic.doors.masterapi.repository.UnifiedAuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class QueryExecutionService {

    private final SqlTemplateRepository sqlTemplateRepository;
    private final AgentRepository agentRepository;
    private final AgentClient agentClient;
    private final UnifiedAuditLogRepository auditlogRepository;

    public QueryExecutionService(SqlTemplateRepository sqlTemplateRepository,
                                 AgentRepository agentRepository,
                                 AgentClient agentClient,
                                 UnifiedAuditLogRepository auditlogRepository) {
        this.sqlTemplateRepository = sqlTemplateRepository;
        this.agentRepository = agentRepository;
        this.agentClient = agentClient;
        this.auditlogRepository = auditlogRepository;
    }

    /**
     * MULTI-AGENT BLOCKING FETCH (System Internal)
     */
    public List<Map<String, Object>> fetchFromAgent(String agentId, String queryName) {
        return executeRemote(agentId, queryName, "SYSTEM", Collections.emptyMap())
                .map(obj -> (Map<String, Object>) obj)
                .collectList()
                .block();
    }

    /**
     * MAIN EXECUTION GATEWAY
     * Handles security checks, audit logging, and reactive streaming.
     */
    @Transactional
    public Flux<Object> executeRemote(String agentId, String queryName, String userId, Map<String, Object> params) {
        // 1. Resolve Agent
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new RuntimeException("Agent not found: " + agentId));

        // 2. Resolve SQL Template
        SqlTemplate template = sqlTemplateRepository.findByUniqueName(queryName)
                .orElseThrow(() -> new RuntimeException("Query template not found: " + queryName));

        // 3. INITIALIZE LAZY COLLECTION (Session Safety)
        if (template.getAuthorizedAgents() != null) {
            template.getAuthorizedAgents().size();
        }

        // 4. SECURITY CHECK: Approval Status
        if (!"APPROVED".equals(template.getStatus())) {
            log.warn("DOORS-SECURITY: Denied unapproved query '{}' for {}", queryName, userId);
            return Flux.error(new SecurityException("Access Denied: Query is not approved."));
        }

        // 5. SECURITY CHECK: Infrastructure Authorization
        if (template.getAuthorizedAgents() == null || !template.getAuthorizedAgents().contains(agentId)) {
            log.error("DOORS-SECURITY ALERT: Unauthorized access attempt to agent {} for query {}", agentId, queryName);
            return Flux.error(new SecurityException("Access Denied: Agent " + agentId + " is not authorized."));
        }

        // 6. Create Unified Audit Log Entry
        // Use query_id from the template to fix the "Null" issue in your logs
        UnifiedAuditLog execLog = UnifiedAuditLog.builder()
                .eventType("QUERY_EXECUTION")
                .queryId(template.getQueryId().intValue())// This was missing before
                .queryName(queryName)
                .endpoint(agent.getBaseUrl())
                .username(userId)
                .statusCode(102) // Status: Processing
                .build();

        // Save initial log to DB
        final UnifiedAuditLog savedLog = auditlogRepository.save(execLog);
        long startTime = System.currentTimeMillis();

        log.info("DOORS-EXECUTION: Dispatching {} (ID: {}) to agent {}", queryName, template.getQueryId(), agentId);

        // 7. Reactive Streaming via AgentClient
        return agentClient.streamData(agent.getBaseUrl(), template.getSqlText(), userId, params)
                .map(data -> {
                    // Postgres JSON aggregate handling
                    if (data instanceof Map) {
                        Map<String, Object> map = (Map<String, Object>) data;
                        if (map.containsKey("json_agg")) {
                            Object jsonWrapper = map.get("json_agg");
                            if (jsonWrapper instanceof Map) {
                                Map<String, Object> wrapperMap = (Map<String, Object>) jsonWrapper;
                                return wrapperMap.getOrDefault("value", data);
                            }
                            return jsonWrapper.toString();
                        }
                    }
                    return data;
                })
                .doOnComplete(() -> {
                    long duration = System.currentTimeMillis() - startTime;
                    savedLog.setStatusCode(200);
                    savedLog.setDurationMs(duration);
                    auditlogRepository.save(savedLog);
                    log.info("DOORS-EXECUTION: Success - {} on {} [{}ms]", queryName, agentId, duration);
                })
                .doOnError(error -> {
                    savedLog.setStatusCode(500);
                    savedLog.setErrorMessage(error.getMessage());
                    auditlogRepository.save(savedLog);
                    log.error("DOORS-EXECUTION: Failure - '{}' on {} error: {}", queryName, agentId, error.getMessage());
                });
    }
}