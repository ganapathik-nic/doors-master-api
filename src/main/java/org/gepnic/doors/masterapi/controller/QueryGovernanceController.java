package org.gepnic.doors.masterapi.controller;

import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.model.SqlTemplate;
import org.gepnic.doors.masterapi.service.QueryApprovalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.service.MappingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/master/governance")
@RequiredArgsConstructor
public class QueryGovernanceController {

    private final MappingService mappingService;
    private final QueryApprovalService approvalService;

    /**
     * Updates authorized agents for a specific SQL template.
     * This aligns with the "Real World" requirement for dynamic monitoring.
     */
    @PostMapping("/mapping/{queryId}")
    public ResponseEntity<?> updateMappings(
            @PathVariable Long queryId, 
            @RequestBody List<String> agentInstanceCodes) {
        
        log.info("GEMS-GOVERNANCE: Updating QueryID {} with Agents: {}", queryId, agentInstanceCodes);
        
        try {
            mappingService.updateAgentMappings(queryId, agentInstanceCodes);
            return ResponseEntity.ok(Map.of(
                "success", true, 
                "message", "Agent mappings updated successfully"
            ));
        } catch (Exception e) {
            log.error("GEMS-GOVERNANCE: Update failed for QueryID {}", queryId, e);
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false, 
                "message", "Database update failed: " + e.getMessage()
            ));
        }
    }
/**
     * Fetch all Data Requests approved by Governance but not yet fulfilled by SQL.
     * This populates the "Pending Assignments" for developers.
     */
    @GetMapping("/requests/approved")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getApprovedRequests() {
        log.info("DOORS-MASTER: Fetching approved data requests for developer queue");
        try {
            // Logic: Fetch from data_pull_requests where status = 'APPROVED'
            List<Map<String, Object>> approvedRequests = approvalService.getApprovedDataRequests();
            return ResponseEntity.ok(ApiResponse.success(approvedRequests, "Approved requests fetched"));
        } catch (Exception e) {
            log.error("DOORS-MASTER: Failed to fetch approved requests", e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Failed to load assignments"));
        }
    }
/**
 * Controller for DOORS Governance.
 * Handles the Proposer/Approver workflow for the 50-member team.
 */

/* 
@RestController
@RequestMapping("/api/v1/master/governance")
public class QueryGovernanceController {

    private final QueryApprovalService approvalService;

    // Spring injects the service here automatically
    public QueryGovernanceController(QueryApprovalService approvalService) {
        this.approvalService = approvalService;
    }
*/
    /**
     * Propose a new SQL query for MIS/Reporting.
     * Accessible by the 50-member team.
     */
    @PostMapping("/propose")
public ResponseEntity<ApiResponse<SqlTemplate>> propose(
        @RequestBody SqlTemplate template, 
        @RequestParam String userId) {
    
    // Call your existing service logic
    SqlTemplate proposed = approvalService.proposeQuery(template, userId);
    
    // Return the standardized enterprise response
    return ResponseEntity.ok(
        ApiResponse.success(proposed, "Query '" + template.getUniqueName() + "' proposed successfully")
    );
}
    /**
     * List all PENDING queries for Admin review.
     */
 /*    @GetMapping("/pending")
    public ResponseEntity<List<SqlTemplate>> listPending() {
        return ResponseEntity.ok(approvalService.getPendingQueries());
    }
*/

@GetMapping("/pending")
public ResponseEntity<ApiResponse<List<SqlTemplate>>> listPending() {
    List<SqlTemplate> list = approvalService.getPendingQueries();
    return ResponseEntity.ok(ApiResponse.success(list, "Pending queries fetched successfully"));
}
    /**
     * Admin approval endpoint.
     * Moves a query from PENDING to APPROVED.
     */
    @PostMapping("/approve/{id}")
    public ResponseEntity<SqlTemplate> approve(
            @PathVariable Long id, 
            @RequestParam String adminId) {
        return ResponseEntity.ok(approvalService.approveQuery(id, adminId));
    }
}