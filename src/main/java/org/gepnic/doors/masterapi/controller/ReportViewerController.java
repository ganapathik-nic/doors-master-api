package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.dto.ReportExecutionRequest;
import org.gepnic.doors.masterapi.dto.SelectionOption;
import org.gepnic.doors.masterapi.service.ReportViewerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * MASTER: Hardened Report Viewer Controller
 * Implements Principal-based identity verification to prevent IDOR and Parameter Tampering.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/reports")
public class ReportViewerController {

    private final ReportViewerService reportViewerService;

    /**
     * SECURE: Get authorized SQL Templates.
     * Identity is derived from the JWT Principal, not a RequestParam.
     */
    @GetMapping("/templates")
    public ResponseEntity<ApiResponse<List<SelectionOption>>> getAuthorizedTemplates(Principal principal) {
        String authenticatedUser = principal.getName();
        log.info("DOORS-SECURE: Fetching templates for authenticated user: {}", authenticatedUser);
        
        try {
            List<SelectionOption> templates = reportViewerService.getQueriesForUser(authenticatedUser);
            return ResponseEntity.ok(ApiResponse.success(templates, "Authorized templates retrieved"));
        } catch (Exception e) {
            log.error("DOORS-ERROR: Template fetch failed for {}", authenticatedUser, e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Failed to load authorized templates", 500));
        }
    }

    /**
     * SECURE: Get authorized Agents for a specific template.
     * Prevents users from probing agents they aren't mapped to.
     */
    @GetMapping("/agents")
    public ResponseEntity<ApiResponse<List<SelectionOption>>> getAuthorizedAgents(
            Principal principal,
            @RequestParam Long queryId) {
        
        String authenticatedUser = principal.getName();
        log.info("DOORS-SECURE: Fetching authorized agents for user: {} and query: {}", authenticatedUser, queryId);
        
        try {
            List<SelectionOption> agents = reportViewerService.getAgentsForUserAndQuery(authenticatedUser, queryId);
            return ResponseEntity.ok(ApiResponse.success(agents, "Authorized agents retrieved"));
        } catch (Exception e) {
            log.error("DOORS-ERROR: Agent fetch failed for {}", authenticatedUser, e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Error loading authorized agents", 500));
        }
    }

    /**
     * SECURE: Executes the specific SQL template on the target agent.
     * Forces the 'performedBy' field to match the JWT identity.
     */
    @PostMapping("/execute")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> executeReport(
            @RequestBody ReportExecutionRequest request,
            Principal principal) {

        String authenticatedUser = principal.getName();
        
        // 🛡️ SECURITY OVERRIDE: 
        // Ignore whatever 'performedBy' was sent in the JSON body.
        // Use the identity verified by the JWT Filter.
        request.setPerformedBy(authenticatedUser);

        log.info("DOORS-SECURE: Execution request for query: {} on agent: {} by: {}", 
                request.getQueryId(), request.getAgentId(), authenticatedUser);

        try {
            List<Map<String, Object>> resultData = reportViewerService.executeReport(request);
            
            return ResponseEntity.ok(ApiResponse.success(resultData, 
                    "Report executed successfully. " + resultData.size() + " rows returned."));
        } catch (SecurityException se) {
            // This catches the 403 logic from your service layer
            log.warn("🚨 SECURITY VIOLATION: {} attempted unauthorized pull on agent {}", 
                    authenticatedUser, request.getAgentId());
            return ResponseEntity.status(403)
                    .body(ApiResponse.error(se.getMessage(), 403));
        } catch (Exception e) {
            log.error("DOORS-ERROR: Execution failed for user: {}", authenticatedUser, e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Query Execution Failed: " + e.getMessage(), 500));
        }
    }
}