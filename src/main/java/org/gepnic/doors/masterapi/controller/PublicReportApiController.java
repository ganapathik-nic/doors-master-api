package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.dto.ReportExecutionRequest;
import org.gepnic.doors.masterapi.service.ReportViewerService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

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

        // 1. Resolve Client Identity from the correct table
        String sql = "SELECT client_name FROM external_api_clients WHERE api_key = ? AND is_active = true";
        String resolvedClient;
        
        try {
            resolvedClient = jdbcTemplate.queryForObject(sql, String.class, apiKey);
        } catch (Exception e) {
            log.error("DOORS-AUTH: Invalid or inactive API Key: {}", apiKey);
            return ApiResponse.error("Governance Error: Invalid API Passport", 403);
        }

        // 2. Extract agentIds from payload
        @SuppressWarnings("unchecked")
        List<String> agentIds = (List<String>) payload.get("agentIds");

        // 3. Build DTO: Force identity to the resolved client name
        ReportExecutionRequest request = ReportExecutionRequest.builder()
                .queryUniqueName(uniqueName)
                .agentId(agentIds != null ? String.join(",", agentIds) : "")
                .performedBy(resolvedClient) 
                .build();

        log.info("DOORS-ORCHESTRATOR: Request by [{}] for query [{}]", resolvedClient, uniqueName);

        // 4. Update usage timestamp
        jdbcTemplate.update("UPDATE external_api_clients SET last_used_at = CURRENT_TIMESTAMP WHERE api_key = ?", apiKey);

        // 5. Execute via shared service logic
        List<Map<String, Object>> data = reportService.executeReport(request);
        
        return ApiResponse.success(data, "Identity Verified: " + resolvedClient);
    }
}