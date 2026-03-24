package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.dto.ReportExecutionRequest;
import org.gepnic.doors.masterapi.dto.SelectionOption;
import org.gepnic.doors.masterapi.service.ReportViewerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Controller for the Interactive Report Viewer within the DOORS Governance Framework.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/reports")
public class ReportViewerController {

    private final ReportViewerService reportViewerService;

    /**
     * Get authorized SQL Templates for the logged-in user.
     * Path: GET /api/v1/reports/templates?username=admin
     */
    @GetMapping("/templates")
    public ResponseEntity<ApiResponse<List<SelectionOption>>> getAuthorizedTemplates(
            @RequestParam String username) {
        
        log.info("DOORS-GOVERNANCE: Fetching templates for user: {}", username);
        
        try {
            // Service returns SelectionOption objects which now include the parameters list
            List<SelectionOption> templates = reportViewerService.getQueriesForUser(username);
            return ResponseEntity.ok(ApiResponse.success(templates, "Authorized templates retrieved"));
        } catch (Exception e) {
            log.error("DOORS-GOVERNANCE: Error fetching templates for {}", username, e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Error loading templates: " + e.getMessage(), 500));
        }
    }

    /**
     * Get authorized Agents for a specific template based on user permissions.
     * Path: GET /api/v1/reports/agents?username=admin&queryId=101
     */
    @GetMapping("/agents")
    public ResponseEntity<ApiResponse<List<SelectionOption>>> getAuthorizedAgents(
            @RequestParam String username,
            @RequestParam Long queryId) {
        
        log.info("DOORS-REPORT: Fetching authorized agents for user: {} and query: {}", username, queryId);
        
        try {
            List<SelectionOption> agents = reportViewerService.getAgentsForUserAndQuery(username, queryId);
            return ResponseEntity.ok(ApiResponse.success(agents, "Authorized agents retrieved"));
        } catch (Exception e) {
            log.error("DOORS-REPORT: Failed to fetch agents for user {} and query {}", username, queryId, e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Error loading agents: " + e.getMessage(), 500));
        }
    }

    /**
     * Executes the specific SQL template on the target agent.
     * Path: POST /api/v1/reports/execute
     */
    @PostMapping("/execute")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> executeReport(
            @RequestBody ReportExecutionRequest request) {

        log.info("DOORS-REPORT: Execution request received for query: {} on agent: {} by: {}", 
                request.getQueryId(), request.getAgentId(), request.getPerformedBy());

        try {
            List<Map<String, Object>> resultData = reportViewerService.executeReport(request);
            
            return ResponseEntity.ok(ApiResponse.success(resultData, 
                    "Report executed successfully. " + resultData.size() + " rows returned."));
        } catch (Exception e) {
            log.error("DOORS-REPORT: Execution failed for query ID: {}", request.getQueryId(), e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Query Execution Failed: " + e.getMessage(), 500));
        }
    }
}