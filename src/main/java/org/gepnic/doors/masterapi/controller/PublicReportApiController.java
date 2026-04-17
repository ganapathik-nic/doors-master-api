package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.dto.ReportExecutionRequest;
import org.gepnic.doors.masterapi.service.ReportViewerService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/master/reports")
@RequiredArgsConstructor
public class PublicReportApiController {

    private final ReportViewerService reportService;
    private final JdbcTemplate jdbcTemplate; // Added this field

    /**
     * Orchestrates cross-agent data retrieval for external API clients.
     * Identity is resolved from external_api_clients via X-API-KEY.
     */
  @PostMapping("/orchestrate/{uniqueName}")
public ApiResponse<List<Map<String, Object>>> orchestrateApiCall(
        @PathVariable String uniqueName,
        @RequestHeader("X-API-KEY") String apiKey,
        @RequestBody Map<String, Object> payload) {

    // 1. Resolve Client Identity (Keep your existing logic)
    String sql = "SELECT client_name FROM external_api_clients WHERE api_key = ? AND is_active = true";
    String resolvedClient;
    try {
        resolvedClient = jdbcTemplate.queryForObject(sql, String.class, apiKey);
    } catch (Exception e) {
        return ApiResponse.error("Governance Error: Invalid API Passport", 403);
    }

    // 2. Extract agentIds AND params 🛡️ (The missing part)
    @SuppressWarnings("unchecked")
    List<String> agentIds = (List<String>) payload.get("agentIds");
    
    @SuppressWarnings("unchecked")
    Map<String, Object> params = (Map<String, Object>) payload.get("params");

    // 3. Build DTO: Now including params for scanning
    ReportExecutionRequest request = ReportExecutionRequest.builder()
            .queryUniqueName(uniqueName)
            .agentId(agentIds != null ? String.join(",", agentIds) : "ALL")
            .performedBy(resolvedClient) 
            .params(params != null ? params : new HashMap<>()) // 🚀 Pass params here!
            .build();

    log.info("DOORS-ORCHESTRATOR: Secure Request by [{}] for [{}]", resolvedClient, uniqueName);

    try {
        // 4. Update timestamp
        jdbcTemplate.update("UPDATE external_api_clients SET last_used_at = CURRENT_TIMESTAMP WHERE api_key = ?", apiKey);

        // 5. Execute via shared service (which will now see and block the injection)
        List<Map<String, Object>> data = reportService.executeReport(request);
        return ApiResponse.success(data, "Identity Verified: " + resolvedClient);

    } catch (SecurityException se) {
        // 🛡️ Explicitly catch the hardening trigger from the Service
        log.warn("🚨 API SECURITY ALERT: Malicious attempt by client [{}]. Error: {}", resolvedClient, se.getMessage());
        return ApiResponse.error(se.getMessage(), 403);
    } catch (Exception e) {
        log.error("DOORS-ERROR: Orchestration failed", e);
        return ApiResponse.error("Execution Failed: " + e.getMessage(), 500);
    }
}
}