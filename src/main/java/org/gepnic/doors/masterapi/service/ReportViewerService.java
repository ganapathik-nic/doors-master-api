package org.gepnic.doors.masterapi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.config.AuditContextHolder;
import org.gepnic.doors.masterapi.dto.ReportExecutionRequest;
import org.gepnic.doors.masterapi.dto.SelectionOption;
import org.gepnic.doors.masterapi.dto.ReportResult;
import org.gepnic.doors.masterapi.model.Agent; 
import org.gepnic.doors.masterapi.repository.AgentRepository; 
import org.gepnic.doors.masterapi.repository.ReportMappingRepository;
import org.gepnic.doors.masterapi.util.EncryptionUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.Array;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReportViewerService {

    private static final String ATTR_TOTAL_RECORD_COUNT = "TOTAL_RECORD_COUNT";
    private static final String INFRA_TRANSIT_SECRET = "DOORS_VLAN_INTERNAL_SECRET_KEY_2026";
    
    private final ObjectMapper objectMapper;
    private final ReportMappingRepository mappingRepository;
    private final AgentRepository agentRepository; 
    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;

    // 🛡️ SQL Injection Pattern for Text-based Scanning
    private static final Pattern SQL_INJECTION_PATTERN = Pattern.compile(
        "(?i)(--|;|\\bUNION\\b|\\bSELECT\\b|\\bDROP\\b|\\bUPDATE\\b|\\bDELETE\\b|\\bOR\\b[\\s'\"\\d]*=[\\s'\"\\d]*)", 
        Pattern.CASE_INSENSITIVE
    );

    @Transactional(readOnly = true)
    public List<SelectionOption> getQueriesForUser(String username) {
        List<Object[]> results = mappingRepository.findTemplatesByAgentIntersection(username);
        return results.stream().map(row -> {
            List<String> paramsList = new ArrayList<>();
            try {
                if (row.length > 2 && row[2] != null) {
                    if (row[2] instanceof Array sqlArray) {
                        String[] pArray = (String[]) sqlArray.getArray();
                        if (pArray != null) paramsList = Arrays.asList(pArray);
                    } else if (row[2] instanceof String[] stringArray) {
                        paramsList = Arrays.asList(stringArray);
                    }
                }
            } catch (Exception e) {
                log.error("DOORS-MASTER: Param parsing failed", e);
            }
            return new SelectionOption(String.valueOf(row[0]), String.valueOf(row[1]), paramsList);
        }).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<SelectionOption> getAgentsForUserAndQuery(String username, Long queryId) {
        return mappingRepository.findIntersectionAgents(username, queryId)
                .stream()
                .map(result -> new SelectionOption(
                        String.valueOf(result[0]),
                        String.valueOf(result[1]) + " (" + String.valueOf(result[0]) + ")"
                )).collect(Collectors.toList());
    }

    public ReportResult executeReport(ReportExecutionRequest request) {
        List<Map<String, Object>> aggregatedResults = new ArrayList<>();
        List<String> offlineAgents = new ArrayList<>();
        int actualAuditCount = 0;

        try {
            // 🛡️ 1. Security Scan: Input Parameters
            validateParameters(request.getParams());

            // 🛡️ 2. Security Scan: Unauthorized Agents
            List<String> targetAgentIds = resolveAgents(request);

            String sql = resolveSql(request);
            Map<String, Object> sanitizedParams = sanitizeParams(request.getParams());

            for (String agentId : targetAgentIds) {
                String agentIdTrimmed = agentId.trim();
                try {
                    // Fetch the exact unique entity object from the master registry by UI ID selection
                    Agent agent = agentRepository.findById(agentIdTrimmed)
                            .orElseThrow(() -> new NoSuchElementException("Agent registry record missing for ID: " + agentIdTrimmed));

                    String rawBaseUrl = agent.getBaseUrl() != null ? agent.getBaseUrl().trim() : "";
                    while (rawBaseUrl.endsWith("/")) {
                        rawBaseUrl = rawBaseUrl.substring(0, rawBaseUrl.length() - 1);
                    }

                    // 🚀 THE ARCHITECTURE REFACTOR ROUTER
                    int lastSlashIndex = rawBaseUrl.lastIndexOf("/");
                    if (lastSlashIndex == -1 || lastSlashIndex < rawBaseUrl.indexOf("://") + 3) {
                        throw new IllegalArgumentException("Malformed base_url in registry for agent: " + agentIdTrimmed);
                    }

                    // e.g., "assam" or "dev-01"
                    String extractedInstanceCode = rawBaseUrl.substring(lastSlashIndex + 1); 
                    
                    // e.g., "https://demoetenders.tn.nic.in/doorsagent" or "http://127.0.0.1:8051"
                    String proxyNetworkRoot = rawBaseUrl.substring(0, lastSlashIndex); 
                    
                    // 🎯 Dynamically append the exact endpoint path to the clean proxy root context
                    String endpoint = proxyNetworkRoot + "/v1/agent/query/execute";

                    // Declare loop-isolated connection parameters explicitly
                    String targetHost = agent.getTargetDbHost() != null ? agent.getTargetDbHost().trim() : "";
                    String targetDb   = agent.getTargetDbName() != null ? agent.getTargetDbName().trim() : "";
                    String targetUser = agent.getTargetDbUser() != null ? agent.getTargetDbUser().trim() : "";
                    int targetPort    = agent.getTargetDbPort() != null ? agent.getTargetDbPort() : 5432;

                    // 🎯 EXPLICIT CONSOLE OUT LOUDSPEAKER
                    log.info("=================================================================================");
                    log.info("DOORS-MASTER CONSOLE TRACKER:");
                    log.info("  -> Target Agent ID  : [{}]", agentIdTrimmed);
                    log.info("  -> Isolated Suffix  : [{}]", extractedInstanceCode);
                    log.info("  -> Mapped Proxy Root: [{}]", proxyNetworkRoot);
                    log.info("  -> TARGET ENDPOINT  : [{}]", endpoint);
                    log.info("  -> WIRE PARAMETERS  : Host=[{}], Port=[{}], DB=[{}], User=[{}]", 
                            targetHost, targetPort, targetDb, targetUser);
                    log.info("=================================================================================");

                    // Build the payload injection frame
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("sql", sql);
                    payload.put("executedBy", request.getPerformedBy());
                    payload.put("params", sanitizedParams);
                    payload.put("agentType", agent.getAgentType());
                    payload.put("instanceCode", extractedInstanceCode); 

                    // Inject validated database fields directly into the outbound envelope
                    payload.put("dbHost", targetHost);
                    payload.put("dbPort", targetPort);
                    payload.put("dbName", targetDb);
                    payload.put("dbUser", targetUser);
                    
                    if (agent.getTargetDbPassword() != null && !agent.getTargetDbPassword().isBlank()) {
                        String encryptedPass = EncryptionUtils.encrypt(agent.getTargetDbPassword().trim(), INFRA_TRANSIT_SECRET);
                        payload.put("dbPasswordSecure", encryptedPass);
                    } else {
                        payload.put("dbPasswordSecure", "");
                    }

                    // 🛡️ Deliver payload to the dynamically derived proxy endpoint
                    ResponseEntity<String> response = restTemplate.postForEntity(endpoint, payload, String.class);
                    String rawBody = response.getBody();

                    if (rawBody != null && !rawBody.isBlank()) {
                        List<Map<String, Object>> rows = extractRows(rawBody);

                        for (Map<String, Object> complexRow : rows) {
                            Map<String, Object> flatRow = new HashMap<>();
                            flatRow.put("NODE_ID", agentIdTrimmed);

                            complexRow.forEach((key, value) -> {
                                if (value instanceof Map) {
                                    ((Map<String, Object>) value).forEach(flatRow::putIfAbsent);
                                } else if (value instanceof Collection || isJsonArrayString(value)) {
                                    flatRow.put(key, convertToStandardList(value));
                                } else {
                                    flatRow.putIfAbsent(key, value);
                                }
                            });
                            aggregatedResults.add(flatRow);
                        }
                        actualAuditCount += rows.size();
                    }
                } catch (Exception nodeEx) {
                    log.error("DOORS-MASTER: Node [{}] execution pipe failure: {}", agentIdTrimmed, nodeEx.getMessage(), nodeEx);
                    offlineAgents.add(agentIdTrimmed);
                }
            }
            setRecordCountForAudit(actualAuditCount);
            return new ReportResult(aggregatedResults, offlineAgents);

        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            log.error("DOORS-MASTER: Critical failure during report execution", e);
            throw e;
        }
    }

    /**
     * 🛡️ Scans text parameters for malicious SQL patterns to trigger 403 Forbidden.
     */
    private void validateParameters(Map<String, Object> params) {
        if (params == null) return;
        for (Object value : params.values()) {
            if (value instanceof String strValue) {
                if (SQL_INJECTION_PATTERN.matcher(strValue).find()) {
                    log.error("🚨 SECURITY ALERT: SQL Injection pattern detected in input: [{}]", strValue);
                    throw new SecurityException("Security Violation: Malicious patterns detected in input.");
                }
            }
        }
    }

    /**
     * 🛡️ STRICT VALIDATION: Ensures every requested agent is actually mapped to the user.
     */
    private List<String> resolveAgents(ReportExecutionRequest request) {
        String identity = request.getPerformedBy();
        Long queryId = request.getQueryId();

        if (queryId == null && request.getQueryUniqueName() != null) {
            queryId = jdbcTemplate.queryForObject(
                "SELECT query_id FROM sql_templates WHERE unique_name = ?", 
                Long.class, request.getQueryUniqueName());
        }

        List<String> authorized = mappingRepository.findIntersectionAgents(identity, queryId).stream()
                .map(result -> String.valueOf(result[0])).collect(Collectors.toList());

        if (authorized.isEmpty()) {
            throw new SecurityException("No authorized agents found for this user and query.");
        }

        String requested = request.getAgentId();
        if (requested == null || requested.isBlank() || "ALL".equalsIgnoreCase(requested)) {
            return authorized;
        }

        List<String> requestedList = Arrays.stream(requested.split(","))
                .map(String::trim)
                .collect(Collectors.toList());

        for (String agent : requestedList) {
            if (!authorized.contains(agent)) {
                log.error("🚨 SECURITY VIOLATION: User {} attempted to access unauthorized agent: {}", identity, agent);
                throw new SecurityException("Access Denied: You are not authorized to access agent " + agent);
            }
        }
        return requestedList;
    }

    private String resolveSql(ReportExecutionRequest request) {
        String sql = (request.getQueryId() != null)
                ? mappingRepository.findSqlByQueryId(request.getQueryId())
                : mappingRepository.findSqlByUniqueName(request.getQueryUniqueName());
        if (sql == null || sql.trim().isEmpty()) throw new IllegalStateException("SQL Template not found");
        return sql;
    }

    private String buildEndpoint(String agentUrl) {
        String normalized = (agentUrl != null && agentUrl.endsWith("/")) ? agentUrl.substring(0, agentUrl.length() - 1) : agentUrl;
        return normalized + "/v1/agent/query/execute";
    }

    // --- Helper Methods (JSON Parsing & Audit) ---

    private boolean isJsonArrayString(Object value) {
        if (value instanceof String) {
            String str = ((String) value).trim();
            return str.startsWith("[") && str.endsWith("]");
        }
        return false;
    }

    private List<Map<String, Object>> convertToStandardList(Object obj) {
        if (obj == null) return new ArrayList<>();
        try {
            if (obj instanceof String) obj = objectMapper.readTree((String) obj);
            return objectMapper.convertValue(obj, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void setRecordCountForAudit(int finalCount) {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            attrs.getRequest().setAttribute(ATTR_TOTAL_RECORD_COUNT, finalCount);
        }
        AuditContextHolder.setRecordCount(finalCount);
    }

    private List<Map<String, Object>> extractRows(String rawBody) throws Exception {
        JsonNode root = objectMapper.readTree(rawBody);
        if (root == null || root.isNull()) return Collections.emptyList();
        JsonNode extracted = findRecordsArrayRecursively(root);
        if (extracted != null && extracted.isArray()) return toRowList(extracted);
        if (root.isObject()) return List.of(toRowMap(root));
        return Collections.emptyList();
    }

    private JsonNode findRecordsArrayRecursively(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) {
            String text = node.asText();
            if (text != null && text.trim().startsWith("[")) {
                try { return findRecordsArrayRecursively(objectMapper.readTree(text)); } catch (Exception ignored) {}
            }
            return null;
        }
        if (node.isArray()) {
            if (node.isEmpty()) return node;
            JsonNode first = node.get(0);
            if (first != null && first.isObject() && !looksLikeWrapperObject(first)) return node;
            for (JsonNode element : node) {
                JsonNode found = findRecordsArrayRecursively(element);
                if (found != null) return found;
            }
            return null;
        }
        if (node.isObject()) {
            List<String> preferredKeys = List.of("data", "rows", "result", "results", "items", "value", "json_agg");
            for (String key : preferredKeys) {
                JsonNode found = findRecordsArrayRecursively(node.get(key));
                if (found != null) return found;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                JsonNode found = findRecordsArrayRecursively(fields.next().getValue());
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean looksLikeWrapperObject(JsonNode objectNode) {
        if (objectNode == null || !objectNode.isObject()) return false;
        List<String> wrapperKeys = List.of("json_agg", "data", "rows", "result", "results", "items", "value", "type");
        int fieldCount = 0;
        Iterator<String> names = objectNode.fieldNames();
        while (names.hasNext()) { names.next(); fieldCount++; }
        if (fieldCount == 1) {
            for (String key : wrapperKeys) if (objectNode.has(key)) return true;
        }
        return objectNode.has("type") && objectNode.has("value");
    }

    private List<Map<String, Object>> toRowList(JsonNode node) {
        return objectMapper.convertValue(node, new TypeReference<List<Map<String, Object>>>() {});
    }

    private Map<String, Object> toRowMap(JsonNode node) {
        return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> sanitizeParams(Map<String, Object> input) {
        if (input == null) return new HashMap<>();
        Map<String, Object> sanitized = new HashMap<>();
        input.forEach((key, value) -> {
            if (value == null) { sanitized.put(key, null); return; }
            String text = value.toString().trim();
            if (text.matches("^\\d+$")) {
                 try { sanitized.put(key, Long.parseLong(text)); } catch (Exception e) { sanitized.put(key, value); }
            } else { sanitized.put(key, value); }
        });
        return sanitized;
    }
}