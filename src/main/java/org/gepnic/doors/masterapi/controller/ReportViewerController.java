package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.dto.ReportExecutionRequest;
import org.gepnic.doors.masterapi.dto.SelectionOption;
import org.gepnic.doors.masterapi.dto.ReportResult; // 🛡️ Import your new Record
import org.gepnic.doors.masterapi.service.ReportViewerService;
import org.gepnic.doors.masterapi.service.ReportExportService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;  // <--- THIS IS THE MISSING LINE
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/reports")
public class ReportViewerController {

    private final ReportViewerService reportViewerService;
    private final ReportExportService reportExportService;

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
    private final ObjectMapper objectMapper = new ObjectMapper();
 @PostMapping("/execute")
public ResponseEntity<?> executeReport(
        @RequestBody ReportExecutionRequest request,
        jakarta.servlet.http.HttpServletRequest httpRequest, 
        Principal principal) {

    String authenticatedUser = principal.getName();
    request.setPerformedBy(authenticatedUser);

    try {
        ReportResult result = reportViewerService.executeReport(request);
        ApiResponse<ReportResult> finalResponse = ApiResponse.success(result, "Success");

        try {
            String jsonResponse = objectMapper.writeValueAsString(finalResponse);
            String authHeader = httpRequest.getHeader("Authorization");
            String hybridKey = "D00RS-NIC-SECURE"; // Default

            if (authHeader != null && authHeader.contains("Bearer ")) {
                // Remove prefix and any potential whitespace
                String rawJwt = authHeader.replace("Bearer ", "").trim();
                
                // 🚀 ENSURE WE HAVE AT LEAST 8 CHARS TO SLICE
                if (rawJwt.length() >= 8) {
                    String systemPart = "D00RS-NI"; 
                    String userPartSlice = rawJwt.substring(rawJwt.length() - 8);
                    hybridKey = systemPart + userPartSlice;
                }
            }

            // 🕵️‍♂️ CRITICAL: This MUST match the browser F12 console exactly
            System.out.println("DOORS_DEBUG_KEY: [" + hybridKey + "]");

            String encryptedPayload = org.gepnic.doors.masterapi.util.EncryptionUtils.encrypt(jsonResponse, hybridKey);

            return ResponseEntity.ok()
                    .header("X-Content-Secure", "true")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("secureData", encryptedPayload, "info", "Hybrid Encrypted"));

        } catch (Exception encryptEx) {
            log.error("Encryption Failure", encryptEx);
            return ResponseEntity.ok(finalResponse);
        }
    } catch (Exception e) {
        return ResponseEntity.status(500).body(ApiResponse.error("Execution Failed", 500));
    }
}

    @PostMapping("/exports")
    public ResponseEntity<ApiResponse<Map<String, Object>>> startExport(
            @RequestBody ReportExecutionRequest request,
            Principal principal) {
        request.setPerformedBy(principal.getName());
        return ResponseEntity.accepted().body(ApiResponse.success(
                reportExportService.start(request, principal.getName()),
                "Export queued"
        ));
    }

    @GetMapping("/exports/{jobId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> exportStatus(
            @PathVariable java.util.UUID jobId,
            Principal principal) {
        return ResponseEntity.ok(ApiResponse.success(
                reportExportService.status(jobId, principal.getName()),
                "Export status"
        ));
    }

    @GetMapping("/exports/{jobId}/download")
    public ResponseEntity<org.springframework.core.io.Resource> downloadExport(
            @PathVariable java.util.UUID jobId,
            Principal principal) {
        return reportExportService.download(jobId, principal.getName());
    }
}
