package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.dto.ReportExecutionRequest;
import org.gepnic.doors.masterapi.dto.SelectionOption;
import org.gepnic.doors.masterapi.dto.ReportResult; // 🛡️ Import your new Record
import org.gepnic.doors.masterapi.service.ReportViewerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/reports")
public class ReportViewerController {

    private final ReportViewerService reportViewerService;

    @GetMapping("/templates")
    public ResponseEntity<ApiResponse<List<SelectionOption>>> getAuthorizedTemplates(Principal principal) {
        String authenticatedUser = principal.getName();
        try {
            List<SelectionOption> templates = reportViewerService.getQueriesForUser(authenticatedUser);
            return ResponseEntity.ok(ApiResponse.success(templates, "Authorized templates retrieved"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(ApiResponse.error("Failed to load templates", 500));
        }
    }

    @GetMapping("/agents")
    public ResponseEntity<ApiResponse<List<SelectionOption>>> getAuthorizedAgents(Principal principal, @RequestParam Long queryId) {
        String authenticatedUser = principal.getName();
        try {
            List<SelectionOption> agents = reportViewerService.getAgentsForUserAndQuery(authenticatedUser, queryId);
            return ResponseEntity.ok(ApiResponse.success(agents, "Authorized agents retrieved"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(ApiResponse.error("Error loading agents", 500));
        }
    }

    /**
     * SECURE EXECUTION: Updated to handle ReportResult (Partial Success Model)
     */
    @PostMapping("/execute")
    public ResponseEntity<ApiResponse<ReportResult>> executeReport(
            @RequestBody ReportExecutionRequest request,
            Principal principal) {

        String authenticatedUser = principal.getName();
        request.setPerformedBy(authenticatedUser);

        try {
            // 🛡️ 1. Call the service and receive the new ReportResult Record
            ReportResult result = reportViewerService.executeReport(request);
            
            // 🛡️ 2. Logic for total failure (All target agents are offline)
           if (result.data().isEmpty() && !result.offlineAgents().isEmpty()) {
    List<String> offline = result.offlineAgents();
    String errorMsg;

    if (offline.size() == 1) {
        errorMsg = "Remote agent [" + offline.get(0) + "] is Offline";
    } else {
        errorMsg = "Multiple agents are Offline: " + String.join(", ", offline);
    }

    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ApiResponse.error(errorMsg, 503));
}
            // 🛡️ 3. Construct the dynamic success/partial success message
            String message = result.offlineAgents().isEmpty() 
                ? "Report executed successfully. " + result.data().size() + " rows returned."
                : "Partial Success. Data retrieved from live nodes, but unreachable: " + result.offlineAgents();

            return ResponseEntity.ok(ApiResponse.success(result, message));

        } catch (SecurityException se) {
            log.warn("🚨 SECURITY VIOLATION: {} on agent {}", authenticatedUser, request.getAgentId());
            return ResponseEntity.status(403).body(ApiResponse.error(se.getMessage(), 403));
        } catch (Exception e) {
            log.error("DOORS-ERROR: Execution failed for user: {}", authenticatedUser, e);
            return ResponseEntity.status(500).body(ApiResponse.error("Query Execution Failed: " + e.getMessage(), 500));
        }
    }
}