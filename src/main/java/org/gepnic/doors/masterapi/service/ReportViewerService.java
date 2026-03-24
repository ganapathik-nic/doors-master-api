package org.gepnic.doors.masterapi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.config.AuditContextHolder;
import org.gepnic.doors.masterapi.dto.ReportExecutionRequest;
import org.gepnic.doors.masterapi.dto.SelectionOption;
import org.gepnic.doors.masterapi.repository.ReportMappingRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.Array;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReportViewerService {

    private static final String ATTR_TOTAL_RECORD_COUNT = "TOTAL_RECORD_COUNT";

    private final ObjectMapper objectMapper;
    private final ReportMappingRepository mappingRepository;
    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;

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
public List<Map<String, Object>> executeReport(ReportExecutionRequest request) {
        List<Map<String, Object>> aggregatedResults = new ArrayList<>();
        int actualAuditCount = 0; 

        try {
            String sql = resolveSql(request);
            List<String> targetAgentIds = resolveAgents(request);
            Map<String, Object> sanitizedParams = sanitizeParams(request.getParams());

            for (String agentId : targetAgentIds) {
                try {
                    String agentUrl = fetchAgentUrl(agentId.trim()); 
                    String endpoint = buildEndpoint(agentUrl);

                    Map<String, Object> payload = new HashMap<>();
                    payload.put("sql", sql);
                    payload.put("executedBy", request.getPerformedBy());
                    payload.put("params", sanitizedParams);

                    ResponseEntity<String> response = restTemplate.postForEntity(endpoint, payload, String.class);
                    String rawBody = response.getBody();

                    if (rawBody != null && !rawBody.isBlank()) {
                        List<Map<String, Object>> rows = extractRows(rawBody);

                        for (Map<String, Object> complexRow : rows) {
                            Map<String, Object> flatRow = new HashMap<>();
                            flatRow.put("NODE_ID", agentId.trim());

                            // 🚀 1. DYNAMIC FLATTENING & SUB-ROW DETECTION
                            complexRow.forEach((key, value) -> {
                                if (value instanceof Map) {
                                    // If it's an Object (like TenderDetails), flatten its fields to the root
                                    ((Map<String, Object>) value).forEach(flatRow::putIfAbsent);
                                } 
                                else if (value instanceof Collection || isJsonArrayString(value)) {
                                    // 🚀 If it's a List (like BoQ or Bidders), keep it as a sub-row key
                                    // This is now dynamic - no hardcoded names!
                                    flatRow.put(key, convertToStandardList(value));
                                } 
                                else {
                                    // Standard data field
                                    flatRow.putIfAbsent(key, value);
                                }
                            });

                            aggregatedResults.add(flatRow);
                        }
                        actualAuditCount += rows.size();
                    }
                } catch (Exception nodeEx) {
                    log.error("DOORS-MASTER: Node [{}] error: {}", agentId, nodeEx.getMessage());
                }
            }

            setRecordCountForAudit(actualAuditCount);
            return aggregatedResults;

        } catch (Exception e) {
            log.error("DOORS-MASTER: Critical failure", e);
            throw e;
        }
    }

    /**
     * Helper to check if a String value is actually a stringified JSON Array
     */
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
            // If it's a stringified JSON array, parse it first
            if (obj instanceof String) {
                obj = objectMapper.readTree((String) obj);
            }
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

    private List<String> resolveAgents(ReportExecutionRequest request) {
        String identity = request.getPerformedBy();
        Long queryId = request.getQueryId();
        if (queryId == null && request.getQueryUniqueName() != null) {
            queryId = jdbcTemplate.queryForObject("SELECT query_id FROM sql_templates WHERE unique_name = ?", Long.class, request.getQueryUniqueName());
        }
        List<String> authorized = mappingRepository.findIntersectionAgents(identity, queryId).stream()
                .map(result -> String.valueOf(result[0])).collect(Collectors.toList());
        if (authorized.isEmpty()) throw new IllegalStateException("Unauthorized");
        String requested = request.getAgentId();
        if (requested == null || requested.isBlank() || "ALL".equalsIgnoreCase(requested)) return authorized;
        return Arrays.stream(requested.split(",")).map(String::trim).filter(authorized::contains).collect(Collectors.toList());
    }

    private String fetchAgentUrl(String agentId) {
        return jdbcTemplate.queryForObject("SELECT base_url FROM agents WHERE agent_id = ?", String.class, agentId);
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
        return normalized + "/doorsagent/v1/agent/query/execute";
    }
}