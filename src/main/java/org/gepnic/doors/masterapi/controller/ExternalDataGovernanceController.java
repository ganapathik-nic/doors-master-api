package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.service.OrchestrationRequestService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/governance/requests")
@RequiredArgsConstructor
public class ExternalDataGovernanceController {

    private final OrchestrationRequestService requestService;
@GetMapping("/list")
    public ResponseEntity<?> listRequests(@RequestParam String status) {
        return ResponseEntity.ok(requestService.getRequestsByStatus(status));
    }
    @GetMapping("/{id}/download-evidence")
    public ResponseEntity<org.springframework.core.io.Resource> downloadFile(@PathVariable Long id) {
        // We call the service to get a custom object containing data and filename
        return requestService.downloadRequestAttachment(id);
    }
    @PostMapping(value = "/submit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> submitRequest(
            @RequestPart("requestTitle") String title,
            @RequestPart("targetAgentId") String agentId,
            @RequestPart("justification") String justification,
            @RequestPart(value = "sampleJson", required = false) String sampleJson,
            @RequestPart(value = "file", required = false) MultipartFile file) {

        String requestedBy = SecurityContextHolder.getContext().getAuthentication().getName();
        
        requestService.submitRequest(title, agentId, justification, sampleJson, requestedBy, file);
        
        return ResponseEntity.ok(ApiResponse.success(null, "Request submitted to Governance Hub"));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable Long id) {
        String adminUser = SecurityContextHolder.getContext().getAuthentication().getName();
        requestService.approveAndSync(id, adminUser);
        return ResponseEntity.ok(ApiResponse.success(null, "Request Approved"));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable Long id, @RequestBody Map<String, String> payload) {
        String adminUser = SecurityContextHolder.getContext().getAuthentication().getName();
        String reason = payload.getOrDefault("reason", "No reason provided");
        requestService.rejectRequest(id, reason, adminUser);
        return ResponseEntity.ok(ApiResponse.success(null, "Request Rejected"));
    }
}