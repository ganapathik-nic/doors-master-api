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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;
@Slf4j
@RestController
@RequestMapping("/api/v1/master/api-clients") // Request must include /v1
@RequiredArgsConstructor
public class ApiClientController {

    private final ApiClientRepository apiClientRepository;
    private final ClientQueryMapRepository mappingRepository;
    private final SqlTemplateRepository sqlTemplateRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ApiClientService apiClientService;

   @GetMapping("/list-with-details")
public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listClientsWithDetails() {
    log.info("DOORS-REGISTRY: Fetching client details...");
    List<ApiClient> clients = apiClientRepository.findAll();
    
    List<Map<String, Object>> response = clients.stream().map(client -> {
        Map<String, Object> map = new HashMap<>();
        map.put("clientId", client.getClientId());
        map.put("clientName", client.getClientName());
        map.put("apiKey", client.getApiKey());
        map.put("isActive", client.getIsActive());

        // 🚀 FIX: Instead of map.put("allowed_ips", client.getAllowedIps())
        // We use the virtual getter we added to the ApiClient entity
        map.put("ipWhitelist", client.getIpWhitelist()); 

        // 1. Fetch authorized Query Details
        List<Long> qIds = mappingRepository.findByClientId(client.getClientId())
                .stream().map(ClientQueryMap::getQueryId).collect(Collectors.toList());
        List<SqlTemplate> templates = sqlTemplateRepository.findAllById(qIds);
        
        List<Map<String, Object>> details = templates.stream().map(t -> {
            Map<String, Object> d = new HashMap<>();
            d.put("queryId", t.getQueryId());
            d.put("uniqueName", t.getUniqueName());
            d.put("parameters", t.getParameters()); 
            return d;
        }).collect(Collectors.toList());
        map.put("authorizedDetails", details);

        // 2. Fetch Assigned Target Agents
        List<String> agents = jdbcTemplate.queryForList(
            "SELECT agent_id FROM user_authorized_agents WHERE user_name = ?", 
            String.class, client.getClientName());
        map.put("assignedAgents", agents);

        return map;
    }).collect(Collectors.toList());

    return ResponseEntity.ok(ApiResponse.success(response, "Success"));
}

    /**
     * Updates the specific campus nodes (agents) authorized for an API client
     */
    @Transactional
    @PostMapping("/{clientId}/authorize-agents")
    public ResponseEntity<ApiResponse<String>> authorizeAgents(
            @PathVariable Long clientId,
            @RequestBody Map<String, List<String>> payload) {
        
        List<String> agentIds = payload.get("agentIds");
        ApiClient client = apiClientRepository.findById(clientId)
                .orElseThrow(() -> new RuntimeException("Client not found"));

        // Clear existing node mappings for this client identity
        jdbcTemplate.update("DELETE FROM user_authorized_agents WHERE user_name = ?", client.getClientName());

        // Bulk insert new authorized agents
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
public ResponseEntity<ApiResponse<ApiClient>> register(@RequestBody Map<String, String> payload) {
    ApiClient client = new ApiClient();
    
    // Log the payload to your console to see exactly what is arriving from Vue
    System.out.println("DEBUG: Register Payload: " + payload);

    client.setClientName(payload.get("clientName"));
    client.setDescription(payload.get("description"));
    client.setCreatedBy(payload.get("createdBy"));
    
    // 🚀 USE THE SAME KEY NAME AS YOUR SECURITY MODULE
    // If your Vue code sends { ipWhitelist: "..." }, use "ipWhitelist" here.
    String ips = payload.get("ipWhitelist"); 
    client.setAllowedIps(ips); 

    client.setIsActive(true);
    String rawKey = UUID.randomUUID().toString().replace("-", "");
    client.setApiKey(Base64.getEncoder().encodeToString(rawKey.getBytes()));
    
    return ResponseEntity.ok(ApiResponse.success(apiClientRepository.save(client), "Registration Successful"));
}
     

    @Transactional
    @PostMapping("/{clientId}/update-mappings")
    public ResponseEntity<ApiResponse<String>> updateMappings(@PathVariable Long clientId, @RequestBody Map<String, List<Long>> payload) {
        List<Long> queryIds = payload.get("queryIds");
        mappingRepository.deleteByClientId(clientId);
        if (queryIds != null && !queryIds.isEmpty()) {
            List<ClientQueryMap> maps = queryIds.stream().map(id -> {
                ClientQueryMap m = new ClientQueryMap();
                m.setClientId(clientId);
                m.setQueryId(id);
                m.setAssignedBy("ADMIN");
                return m;
            }).collect(Collectors.toList());
            mappingRepository.saveAll(maps);
        }
        return ResponseEntity.ok(ApiResponse.success(null, "Access permissions updated"));
    }
@PostMapping("/{clientId}/update-security")
public ResponseEntity<ApiResponse<String>> updateSecurity(
        @PathVariable Long clientId, 
        @RequestBody SecurityUpdateRequest request) {
    // 🚀 DIAGNOSTIC 1: Is the RequestBody null or empty?
    log.info("DOORS-DEBUG: Received Request for Client ID: {}", clientId);
    log.info("DOORS-DEBUG: Payload IP Whitelist: '{}'", request.getIpWhitelist());
    log.info("DOORS-SECURITY: Security update requested for Client ID: {}", clientId);
    
    try {
        apiClientService.updateIpWhitelist(clientId, request.getIpWhitelist());
        
        // Use your project's success method
        return ResponseEntity.ok(ApiResponse.success(null, "Network security policy updated"));
        
    } catch (Exception e) {
        log.error("DOORS-SECURITY: Update failed: {}", e.getMessage());
        
        // 🚀 FIX: Added '500' as the second argument to match your ApiResponse.error(String, int) method
        return ResponseEntity.status(500)
                .body(ApiResponse.error("Failed to update security: " + e.getMessage(), 500));
    }
} 
 // ApiClient.java

@GetMapping(value="/audit-logs/search")
public ResponseEntity<ApiResponse<List<Map<String, Object>>>> searchAuditLogs(
        @RequestParam(required = false) String username,
        @RequestParam(required = false) String action,
        @RequestParam(required = false) String startDate,
        @RequestParam(required = false) String endDate) {

    // 🚀 FIXED: Mapping Postgres column names to Frontend keys
    StringBuilder sql = new StringBuilder(
            "SELECT " +
            "    log_id AS \"log_id\", " + 
            "    execution_time AS \"timestamp\", " + // 👈 Use execution_time here
            "    username, " +
            "    event_type AS \"action_type\", " +
            "    query_name AS \"query_name\", " +
            "    endpoint, " +
            "    method, " +
            "    status_code AS \"response_status\", " + // 👈 Use status_code here
            "    duration_ms AS \"execution_time_ms\", " + // 👈 Use duration_ms here
            "    record_count AS \"record_count\", " +
            "    full_command AS \"full_command\", " +
            "    client_ip AS \"client_ip\" " +
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
        // 🚀 FIXED: Matching execution_time for the date filter
        sql.append(" AND execution_time BETWEEN ?::timestamp AND ?::timestamp ");
        params.add(startDate + " 00:00:00");
        params.add(endDate + " 23:59:59");
    }

    sql.append(" ORDER BY log_id DESC LIMIT 500");

    List<Map<String, Object>> logs = jdbcTemplate.queryForList(sql.toString(), params.toArray());
    return ResponseEntity.ok(ApiResponse.success(logs != null ? logs : new ArrayList<>(), "Audit logs retrieved"));
}
  
 
}