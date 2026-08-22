package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.dto.SecurityUpdateRequest;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.entity.ClientQueryMap;
import org.gepnic.doors.masterapi.model.SqlTemplate;
import org.gepnic.doors.masterapi.repository.ApiClientRepository;
import org.gepnic.doors.masterapi.repository.ClientQueryMapRepository;
import org.gepnic.doors.masterapi.repository.SqlTemplateRepository;
import org.gepnic.doors.masterapi.service.ApiClientService;
import org.gepnic.doors.masterapi.service.ClientSpecificDataSegregationService;
import org.gepnic.doors.masterapi.util.EncryptionUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/master/api-clients")
@RequiredArgsConstructor
public class ApiClientController {

    private final ApiClientRepository apiClientRepository;
    private final ClientQueryMapRepository mappingRepository;
    private final SqlTemplateRepository sqlTemplateRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ApiClientService apiClientService;

    @GetMapping("/list-with-details")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listClientsWithDetails(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        log.info("DOORS-REGISTRY: Fetching client details...");
        List<ApiClient> clients = apiClientRepository.findAll().stream()
                .sorted(Comparator.comparing(ApiClient::getClientName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());

        List<Map<String, Object>> response = clients.stream().map(client -> {
            Map<String, Object> map = new HashMap<>();
            map.put("clientId", client.getClientId());
            map.put("clientName", client.getClientName());
            map.put("apiKey", client.getApiKey());
            map.put("isActive", client.getIsActive());
            map.put("isEncryptionEnabled", client.isEncryptionEnabled());
            map.put("encryptionEnabled", client.isEncryptionEnabled()); 
            map.put("is_encryption_enabled", client.isEncryptionEnabled()); 
            map.put("ipWhitelist", client.getIpWhitelist()); 

            List<Long> qIds = mappingRepository.findByClientId(client.getClientId())
                    .stream().map(ClientQueryMap::getQueryId).collect(Collectors.toList());
            List<SqlTemplate> templates = sqlTemplateRepository.findAllById(qIds);
            
            List<Map<String, Object>> details = templates.stream().map(t -> {
                Map<String, Object> d = new HashMap<>();
                d.put("queryId", t.getQueryId());
                d.put("uniqueName", t.getUniqueName());
                d.put("parameters", t.getParameters()); 
                mappingRepository.findByClientIdAndQueryId(client.getClientId(), t.getQueryId())
                        .ifPresent(mapping -> {
                            d.put("responseFilterColumn", mapping.getResponseFilterColumn());
                            d.put("responseFilterValue", mapping.getResponseFilterValue());
                        });
                return d;
            }).collect(Collectors.toList());
            map.put("authorizedDetails", details);

            List<String> agents = jdbcTemplate.queryForList(
                "SELECT agent_id FROM user_authorized_agents WHERE user_name = ?", 
                String.class, client.getClientName());
            map.put("assignedAgents", agents);

            return map;
        }).collect(Collectors.toList());

        try {
            String hybridKey = buildBrowserResponseKey(authorizationHeader);
            String serializedResponse = new com.fasterxml.jackson.databind.ObjectMapper()
                    .findAndRegisterModules()
                    .writeValueAsString(response);
            Map<String, Object> encryptedEnvelope = new HashMap<>();
            encryptedEnvelope.put("isEncryptedPayload", true);
            encryptedEnvelope.put(
                    "secureData",
                    EncryptionUtils.encrypt(serializedResponse, hybridKey)
            );
            encryptedEnvelope.put("rowCount", response.size());
            return ResponseEntity.ok(ApiResponse.success(encryptedEnvelope, "Success"));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    ApiResponse.error(exception.getMessage(), HttpStatus.UNAUTHORIZED.value())
            );
        } catch (Exception exception) {
            log.error("Unable to encrypt API client details", exception);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    ApiResponse.error(
                            "Unable to secure API client details",
                            HttpStatus.INTERNAL_SERVER_ERROR.value()
                    )
            );
        }
    }

    private String buildBrowserResponseKey(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new IllegalArgumentException("Authorization token is required");
        }
        String rawJwt = authorizationHeader
                .replaceFirst("(?i)^Bearer\\s+", "")
                .trim();
        if (rawJwt.length() < 8) {
            throw new IllegalArgumentException("Authorization token is invalid");
        }
        return "D00RS-NI" + rawJwt.substring(rawJwt.length() - 8);
    }

    @PostMapping("/{clientId}/toggle-encryption")
    public ResponseEntity<ApiResponse<String>> toggleEncryption(
            @PathVariable Long clientId, 
            @RequestBody Map<String, Boolean> payload) {
        
        Boolean encryptionEnabled = payload.get("encryptionEnabled");
        if (encryptionEnabled == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Parameter 'encryptionEnabled' is missing", 400));
        }

        log.info("DOORS-GOVERNANCE: Modifying data-security profile policy rules for Client ID: {}", clientId);
        
        ApiClient client = apiClientRepository.findById(clientId)
                .orElseThrow(() -> new RuntimeException("API Client registry profile not found."));
                
        client.setEncryptionEnabled(encryptionEnabled);
        apiClientRepository.save(client);

        String modeMessage = encryptionEnabled ? "AES-256 Encryption active" : "Plain-text grace period active";
        return ResponseEntity.ok(ApiResponse.success(null, modeMessage));
    }

    @Transactional
    @PostMapping("/{clientId}/authorize-agents")
    public ResponseEntity<ApiResponse<String>> authorizeAgents(
            @PathVariable Long clientId,
            @RequestBody Map<String, List<String>> payload) {
        
        List<String> agentIds = payload.get("agentIds");
        ApiClient client = apiClientRepository.findById(clientId)
                .orElseThrow(() -> new RuntimeException("Client not found"));

        jdbcTemplate.update("DELETE FROM user_authorized_agents WHERE user_name = ?", client.getClientName());

        if (agentIds != null && !agentIds.isEmpty()) {
            String insertSql = "INSERT INTO user_authorized_agents (agent_id, user_id, user_name) VALUES (?, ?, ?)";
            List<Object[]> batchArgs = agentIds.stream()
                    .map(agentId -> new Object[]{agentId, client.getClientId().toString(), client.getClientName()})
                    .collect(Collectors.toList());
            jdbcTemplate.batchUpdate(insertSql, batchArgs);
        }

        log.info("DOORS-GOVERNANCE: Updated node scope for {}", client.getClientName());
        return ResponseEntity.ok(ApiResponse.success(null, "Passport scope updated"));
    }

    @PostMapping("/{clientId}/toggle-status")
    public ResponseEntity<ApiResponse<String>> toggleStatus(@PathVariable Long clientId, @RequestBody Map<String, Boolean> payload) {
        Boolean status = payload.get("active");
        ApiClient client = apiClientRepository.findById(clientId).orElseThrow(() -> new RuntimeException("Client not found"));
        client.setIsActive(status);
        apiClientRepository.save(client);
        return ResponseEntity.ok(ApiResponse.success(null, "Status updated"));
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<String>> register(@RequestBody Map<String, String> payload) {
        ApiClient client = new ApiClient();
        System.out.println("DEBUG: Register Payload: " + payload);

        client.setClientName(payload.get("clientName"));
        client.setDescription(payload.get("description"));
        client.setCreatedBy(payload.get("createdBy"));
        
        String ips = payload.get("ipWhitelist"); 
        client.setAllowedIps(ips); 

        client.setIsActive(true);
        String rawKey = UUID.randomUUID().toString().replace("-", "");
        client.setApiKey(Base64.getEncoder().encodeToString(rawKey.getBytes()));
        
        apiClientRepository.save(client);
        return ResponseEntity.ok(ApiResponse.success(null, "Registration Successful"));
    }

    @Transactional
    @PostMapping("/{clientId}/update-mappings")
    public ResponseEntity<ApiResponse<String>> updateMappings(@PathVariable Long clientId, @RequestBody Map<String, List<Long>> payload) {
        List<Long> queryIds = payload.get("queryIds");
        if (queryIds != null && !queryIds.isEmpty()) {
            List<SqlTemplate> selectedTemplates = sqlTemplateRepository.findAllById(queryIds);
            if (selectedTemplates.size() != new HashSet<>(queryIds).size()) {
                return ResponseEntity.badRequest().body(ApiResponse.error(
                        "One or more selected queries do not exist.", HttpStatus.BAD_REQUEST.value()));
            }
            Optional<SqlTemplate> incompatibleTemplate = selectedTemplates.stream()
                    .filter(template -> template.getUniqueName() == null
                            || !template.getUniqueName().matches("[A-Za-z][A-Za-z0-9_-]{2,99}"))
                    .findFirst();
            if (incompatibleTemplate.isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(
                        "Query Name '" + incompatibleTemplate.get().getUniqueName()
                                + "' is not API-compatible. Remove spaces or unsupported characters through Catalogue before mapping it to an API client.",
                        HttpStatus.CONFLICT.value()));
            }
        }
        Map<Long, ClientQueryMap> existingMappings = mappingRepository.findByClientId(clientId).stream()
                .collect(Collectors.toMap(ClientQueryMap::getQueryId, mapping -> mapping));
        mappingRepository.deleteByClientId(clientId);
        if (queryIds != null && !queryIds.isEmpty()) {
            List<ClientQueryMap> maps = queryIds.stream().map(id -> {
                ClientQueryMap m = new ClientQueryMap();
                m.setClientId(clientId);
                m.setQueryId(id);
                m.setAssignedBy("ADMIN");
                ClientQueryMap previous = existingMappings.get(id);
                if (previous != null) {
                    m.setResponseFilterColumn(previous.getResponseFilterColumn());
                    m.setResponseFilterValue(previous.getResponseFilterValue());
                }
                return m;
            }).collect(Collectors.toList());
            mappingRepository.saveAll(maps);
        }
        return ResponseEntity.ok(ApiResponse.success(null, "Access permissions updated"));
    }

    @PostMapping("/{clientId}/queries/{queryId}/response-filter")
    public ResponseEntity<ApiResponse<String>> updateResponseFilter(
            @PathVariable Long clientId,
            @PathVariable Long queryId,
            @RequestBody Map<String, Object> payload) {
        ClientQueryMap mapping = mappingRepository.findByClientIdAndQueryId(clientId, queryId)
                .orElseThrow(() -> new SecurityException(
                        "Client must be authorized for the data-sharing function before a filter can be configured."));

        boolean enabled = Boolean.TRUE.equals(payload.get("enabled"));
        if (!enabled) {
            mapping.setResponseFilterColumn(null);
            mapping.setResponseFilterValue(null);
        } else {
            String column = Objects.toString(payload.get("filterColumn"), "").trim();
            String value = Objects.toString(payload.get("filterValue"), "").trim();
            if (!ClientSpecificDataSegregationService.isSafeJsonField(column)) {
                return ResponseEntity.badRequest().body(ApiResponse.error(
                        "JSON field must contain only letters, numbers and underscores", 400));
            }
            if (value.isBlank()) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Filter value is required", 400));
            }
            mapping.setResponseFilterColumn(column);
            mapping.setResponseFilterValue(value);
        }

        mappingRepository.save(mapping);
        log.info("DOORS-SEGREGATION: Updated policy for clientId={}, queryId={}, enabled={}",
                clientId, queryId, enabled);
        return ResponseEntity.ok(ApiResponse.success(null, "Client-specific response policy updated"));
    }

    @PostMapping("/{clientId}/update-security")
    public ResponseEntity<ApiResponse<String>> updateSecurity(
            @PathVariable Long clientId, 
            @RequestBody SecurityUpdateRequest request) {
        log.info("DOORS-SECURITY: Security update requested for Client ID: {}", clientId);
        
        try {
            apiClientService.updateIpWhitelist(clientId, request.getIpWhitelist());
            return ResponseEntity.ok(ApiResponse.success(null, "Network security policy updated"));
        } catch (Exception e) {
            log.error("DOORS-SECURITY: Update failed: {}", e.getMessage());
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to update security: " + e.getMessage(), 500));
        }
    }

    /**
     * 🚀 REFACTORED HIGH-PERFORMANCE SEARCH ENGINE
     * Fully synchronized to bind 1:1 against your camelCase AuditLogs.vue attributes!
     */
    @GetMapping("/audit-logs/search")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> searchAuditLogs(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        // ✅ FIXED ALIASES: Explicitly projecting true camelCase keys matching AuditLog interface!
        StringBuilder sql = new StringBuilder(
                "SELECT " +
                "    log_id AS \"logId\", " + 
                "    execution_time AS \"executionTime\", " + 
                "    username AS \"username\", " +
                "    event_type AS \"eventType\", " +
                "    query_name AS \"queryName\", " +
                "    endpoint AS \"endpoint\", " +
                "    method AS \"method\", " +
                "    status_code AS \"statusCode\", " + 
                "    duration_ms AS \"durationMs\", " + 
                "    record_count AS \"recordCount\", " +
                "    client_ip AS \"clientIp\", " +
                "    full_command AS \"fullCommand\", " +
                "    error_code AS \"errorCode\", " +
                "    error_message AS \"errorMessage\", " + // 🎯 ADDED KEY FIELD (Previously missing!)
                "    trace_id AS \"traceId\" " +           // 🎯 ADDED KEY FIELD (Previously missing!)
                "FROM unified_audit_logs " +
                "WHERE 1=1 "
        );
        
        List<Object> params = new ArrayList<>();

        if (username != null && !username.isEmpty()) {
            sql.append(" AND username ILIKE ? ");
            params.add("%" + username + "%");
        }
        
        if (action != null && !action.isEmpty()) {
            sql.append(" AND event_type = ? ");
            params.add(action);
        }
        
        if (startDate != null && !startDate.isEmpty() && endDate != null && !endDate.isEmpty()) {
            sql.append(" AND execution_time BETWEEN ?::timestamp AND ?::timestamp ");
            params.add(startDate + " 00:00:00");
            params.add(endDate + " 23:59:59");
        }

        sql.append(" ORDER BY log_id DESC LIMIT 500");

        List<Map<String, Object>> logs = jdbcTemplate.queryForList(sql.toString(), params.toArray());
        return ResponseEntity.ok(ApiResponse.success(logs != null ? logs : new ArrayList<>(), "Audit logs retrieved"));
    }
}
