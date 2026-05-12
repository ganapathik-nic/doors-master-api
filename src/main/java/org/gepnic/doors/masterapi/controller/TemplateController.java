package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.dto.MappingRequest;
import org.gepnic.doors.masterapi.model.SqlTemplate;
import org.gepnic.doors.masterapi.repository.DataPullRequestRepository;
import org.gepnic.doors.masterapi.repository.SqlTemplateRepository;
import org.gepnic.doors.masterapi.service.AgentExecutionService;
import org.gepnic.doors.masterapi.service.MappingService;
import org.gepnic.doors.masterapi.service.TemplateService;
import org.gepnic.doors.masterapi.util.EncryptionUtils;
import org.gepnic.doors.masterapi.util.SqlSecurityValidator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/v1/master/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final JdbcTemplate jdbcTemplate;
    private final TemplateService templateService;
    private final SqlTemplateRepository sqlTemplateRepository;
    private final MappingService mappingService;
    private final AgentExecutionService agentExecutionService;
    private final DataPullRequestRepository dataPullRequestRepository;

    /**
     * 1. SUBMIT NEW PROPOSAL
     * Force sync via JDBC to ensure request linkage.
     */
    @Transactional
  @PostMapping("/submit")
public ResponseEntity<ApiResponse<Object>> submitTemplate(@RequestBody SqlTemplate template) {
    try {
        // 🛡️ 1. DECRYPT: Turn the incoming AES gibberish back into a SQL string
        String encryptedSql = template.getSqlText();
        String decryptedSql = EncryptionUtils.decrypt(encryptedSql);
        
        // 🛡️ 2. VALIDATE: Check the decrypted plain text for security patterns
        if (!SqlSecurityValidator.isSafeSelectOnly(decryptedSql)) {
            log.warn("DOORS-SECURITY-ALERT: Unauthorized SQL pattern in submission!");
            return ResponseEntity.status(400).body(ApiResponse.error("Security Violation: Only SELECT queries allowed", 400));
        }

        // 🛡️ 3. UPDATE OBJECT: Set the decrypted SQL back into the template for storage
        template.setSqlText(decryptedSql);

    } catch (Exception e) {
        log.error("DOORS-AUTH-CRITICAL: SQL Decryption failed during submission: {}", e.getMessage());
        return ResponseEntity.status(400).body(ApiResponse.error("Invalid Request Payload: Decryption failed", 400));
    }

    // --- Standard logic continues with decrypted text ---
    template.setStatus("PENDING");
    template.setIsActive(true);
    template.setVersion(1);
    template.setCreatedAt(LocalDateTime.now());
    
    SqlTemplate saved = sqlTemplateRepository.save(template);
    
    if (saved.getRequestId() != null && "REQUEST".equalsIgnoreCase(saved.getSubmissionSource())) {
        try {
            jdbcTemplate.update(
                "UPDATE data_pull_requests SET status = ?, query_id = ? WHERE request_id = ?",
                "SQL-Submitted", saved.getQueryId(), saved.getRequestId()
            );
            log.info("DOORS-SYNC: Linked Q-{} to REQ-{}", saved.getQueryId(), saved.getRequestId());
        } catch (Exception e) {
            log.error("DOORS-SYNC-ERROR: {}", e.getMessage());
        }
    }
    return ResponseEntity.ok(ApiResponse.success((Object)saved, "Submitted successfully"));
}

    @GetMapping("/list/my-submissions")
public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMySubmissions(
        @RequestParam String userId,
        @RequestParam(required = false) String role) {
    
    log.info("DOORS-MASTER: Fetching SQL submissions for: {} (Role: {})", userId, role);
    try {
        String sql;
        // 🛡️ FIXED SQL: Removed 'title' (missing in DB) and used 'unique_name' as the title alias
        if ("DataManager".equalsIgnoreCase(role)) {
            sql = "SELECT query_id as \"id\", unique_name as \"uniqueName\", " +
                  "unique_name as \"title\", " + // 👈 Alias unique_name to title
                  "description, status, sql_text as \"sqlQuery\", created_at as \"createdAt\", " +
                  "proposer_id as \"proposerId\" " + 
                  "FROM sql_templates ORDER BY created_at DESC";
            return ResponseEntity.ok(ApiResponse.success(jdbcTemplate.queryForList(sql), "Global history retrieved"));
        } else {
            sql = "SELECT query_id as \"id\", unique_name as \"uniqueName\", " +
                  "unique_name as \"title\", " + // 👈 Alias unique_name to title
                  "description, status, sql_text as \"sqlQuery\", created_at as \"createdAt\" " +
                  "FROM sql_templates WHERE proposer_id = ? ORDER BY created_at DESC";
            return ResponseEntity.ok(ApiResponse.success(jdbcTemplate.queryForList(sql, userId), "Personal history retrieved"));
        }
    } catch (Exception e) {
        log.error("DOORS-DATABASE-ERROR: {}", e.getMessage());
        return ResponseEntity.status(500).body(ApiResponse.error("Database mismatch detected", 500));
    }
}
/* 
 @GetMapping("/list/my-submissions")
public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMySubmissions(@RequestParam String userId) {
    log.info("DOORS-MASTER: Fetching SQL submissions for proposer: {}", userId);
    try {
        // Using 'sql_templates' table and 'proposer_id' column
        String sql = "SELECT query_id as \"id\", unique_name as \"uniqueName\", " +
                     "description, status, governance_note as \"governanceNote\", " +
                     "sql_text as \"sqlText\" " +
                     "FROM sql_templates WHERE proposer_id = ? ORDER BY created_at DESC";
        
        List<Map<String, Object>> submissions = jdbcTemplate.queryForList(sql, userId);
        return ResponseEntity.ok(ApiResponse.success(submissions, "Submissions retrieved"));
    } catch (Exception e) {
        log.error("DOORS-ERROR: Failed to fetch user submissions: {}", e.getMessage());
        return ResponseEntity.status(500).body(ApiResponse.error("Fetch failed: " + e.getMessage(), 500));
    }
}
    */

    /**
     * 2. UPDATE AUTHORIZED NODE MAPPING
     * Fixes the 404 "No static resource" error in Query Library.
     */
    @Transactional
    @PutMapping("/mapping/{queryId}")
    public ResponseEntity<ApiResponse<Object>> updateMapping(
            @PathVariable Long queryId, 
            @RequestBody MappingRequest request) {
        
        log.info("DOORS-MASTER: Updating node mapping for Q-{}", queryId);
        return sqlTemplateRepository.findById(queryId)
            .map(template -> {
                template.setAuthorizedAgents(request.getAgentIds());
                template.setUpdatedAt(LocalDateTime.now());
                sqlTemplateRepository.save(template);
                return ResponseEntity.ok(ApiResponse.success(null, "Mapping updated"));
            })
            .orElse(ResponseEntity.status(404).body(ApiResponse.error("Query not found", 404)));
    }

    /**
     * 3. APPROVAL & STATUS WORKFLOW
     * Finalizes the governance transition.
     */
    @Transactional
    @PutMapping("/status/{id}")
    public ResponseEntity<ApiResponse<Object>> approveAndMap(
            @PathVariable Long id, 
            @RequestBody Map<String, Object> payload) {
        
        return sqlTemplateRepository.findById(id).map(template -> {
            String newStatus = (String) payload.get("status");
            String editedSql = (String) payload.get("sqlText");

            if (editedSql != null && !SqlSecurityValidator.isSafeSelectOnly(editedSql)) {
                return ResponseEntity.status(403).body(ApiResponse.error("Security Violation", 403));
            }

            template.setSqlText(editedSql != null ? editedSql : template.getSqlText());
            template.setStatus(newStatus);
            template.setUpdatedAt(LocalDateTime.now());

            if ("APPROVED".equals(newStatus)) {
                @SuppressWarnings("unchecked")
                List<String> allowedAgents = (List<String>) payload.get("allowedAgents");
                if (allowedAgents != null) template.setAuthorizedAgents(allowedAgents);
                
                if (template.getRequestId() != null) {
                    dataPullRequestRepository.updateStatus(template.getRequestId(), "SQL-Approved");
                }
            }

            SqlTemplate updated = sqlTemplateRepository.save(template);
            return ResponseEntity.ok(ApiResponse.success((Object)updated, "Status: " + newStatus));
        }).orElse(ResponseEntity.status(404).body(ApiResponse.error("Not found", 404)));
    }

    @GetMapping("/by-request/{requestId}")
    public ResponseEntity<ApiResponse<SqlTemplate>> getByRequest(@PathVariable Long requestId) {
        return sqlTemplateRepository.findByRequestId(requestId)
            .map(t -> ResponseEntity.ok(ApiResponse.success(t, "Found")))
            .orElse(ResponseEntity.status(404).body(ApiResponse.error("Not Found", 404)));
    }
/**
     * ENHANCEMENT: Fetch details from the original Data Pull Request.
     * Used by the frontend to auto-populate justification and title.
     */
     @GetMapping("/requests/details/{requestId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRequestDetails(@PathVariable Long requestId) {
        log.info("DOORS-MASTER: Fetching source request details for REQ-{}", requestId);
        try {
            // Updated SQL to match your actual schema:
            // 1. request_id (snake_case)
            // 2. request_title (snake_case)
            // 3. justification (exists)
            // 4. Note: category is missing from your table, so we return a default/null
            String sql = "SELECT request_id, request_title, justification FROM data_pull_requests WHERE request_id = ?";
            
            Map<String, Object> row = jdbcTemplate.queryForMap(sql, requestId);
            
            Map<String, Object> details = new HashMap<>();
            details.put("id", row.get("request_id"));
            details.put("title", row.get("request_title"));
            details.put("justification", row.get("justification"));
            details.put("category", ""); // Table currently lacks 'category' column
            
            return ResponseEntity.ok(ApiResponse.success(details, "Source request details loaded"));
        } catch (Exception e) {
            log.error("DOORS-ERROR: SQL Execution failed. Ensure request_id {} exists. Error: {}", requestId, e.getMessage());
            return ResponseEntity.status(404).body(ApiResponse.error("Request details not found in database", 404));
        }
    }
    @GetMapping("/list")
    public ResponseEntity<ApiResponse<List<SqlTemplate>>> listTemplates(@RequestParam String status) {
        return ResponseEntity.ok(ApiResponse.success(sqlTemplateRepository.findByStatus(status), "Retrieved"));
    }

    @GetMapping("/summary-counts")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getSummaryCounts() {
        List<Object[]> results = sqlTemplateRepository.countByStatusGrouped();
        Map<String, Long> counts = new HashMap<>();
        counts.put("APPROVED", 0L); counts.put("PENDING", 0L);
        counts.put("REJECTED", 0L); counts.put("DISABLED", 0L);
        for (Object[] r : results) { counts.put((String) r[0], (Long) r[1]); }
        return ResponseEntity.ok(ApiResponse.success(counts, "Counts retrieved"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SqlTemplate>> getTemplateById(@PathVariable Long id) {
        return sqlTemplateRepository.findById(id)
            .map(t -> ResponseEntity.ok(ApiResponse.success(t, "Loaded")))
            .orElse(ResponseEntity.status(404).body(ApiResponse.error("Not found", 404)));
    }

@GetMapping("/infrastructure/agents/list/active")
public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getActiveAgents() {
    log.info("DOORS-MASTER: Fetching active remote agents from registry");
    try {
        // Query only ACTIVE agents to filter out ARCHIVED one
        String sql = "SELECT agent_id as \"agentId\", display_name as \"displayName\" " +
                     "FROM agents WHERE is_active =true ORDER BY agent_id ASC";
        
        List<Map<String, Object>> agents = jdbcTemplate.queryForList(sql);
        return ResponseEntity.ok(ApiResponse.success(agents, "Active agents retrieved"));
    } catch (Exception e) {
        log.error("DOORS-ERROR: Failed to fetch agent registry: {}", e.getMessage());
        return ResponseEntity.status(500).body(ApiResponse.error("Registry lookup failed", 500));
    }
}
 
 @PostMapping("/test-dry-run")
public ResponseEntity<ApiResponse<Object>> testQuery(@RequestBody Map<String, Object> payload) {
    // 1. Extract fields with standard DOORS naming conventions
    String agentId = (String) payload.get("agentId");
    
    // 🛡️ FIX: Check for 'base64Sql' first, fallback to 'sqlText' to prevent null 'src'
    String base64Payload = (String) payload.get("base64Sql");
    if (base64Payload == null) {
        base64Payload = (String) payload.get("sqlText");
    }
    
    @SuppressWarnings("unchecked")
    Map<String, Object> params = (Map<String, Object>) payload.get("params");

    try {
        // 2. Validate Agent Selection
        if (agentId == null || agentId.isEmpty()) {
            return ResponseEntity.status(400)
                    .body(ApiResponse.error("Please select a target Agent node", 400));
        }

        // 3. EXECUTE (The service now handles the Base64 decoding & scrubbing internally)
        Map<String, Object> result = agentExecutionService.executeDryRun(agentId, base64Payload, params);

        // 4. SUCCESS RESPONSE
        if (result == null || result.isEmpty()) {
            return ResponseEntity.ok(ApiResponse.success(new HashMap<>(), "Query successful, but returned no data."));
        }
        
        return ResponseEntity.ok(ApiResponse.success((Object)result, "Dry-run completed successfully"));

    } catch (IllegalArgumentException iae) {
        // Catch the Base64/Null errors we added to the Service
        return ResponseEntity.status(400)
                .body(ApiResponse.error(iae.getMessage(), 400));
                
    } catch (SecurityException se) {
        return ResponseEntity.status(403)
                .body(ApiResponse.error("Security Violation: " + se.getMessage(), 403));

    } catch (Exception e) {
        String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown execution error";
        String errorType = e.getClass().getName();
        
        log.error("DOORS-DRYRUN-FAILURE: Type: {}, Message: {}", errorType, errorMsg);

        // 🚀 THE FIX: Catch connection-related errors and transform the message
        if (errorType.contains("Connect") || 
            errorType.contains("Timeout") || 
            errorType.contains("ResourceAccess") || 
            errorMsg.contains("Connection refused") ||
            errorMsg.contains("finishConnect")) {
            
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("Remote agent [" + agentId + "] is Offline or unreachable", 503));
        }

        // Fallback for SQL syntax or other processing errors
        return ResponseEntity.status(400)
                .body(ApiResponse.error("Execution error: " + errorMsg, 400));
    }
}
}