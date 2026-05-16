package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.dto.ReportExecutionRequest;
import org.gepnic.doors.masterapi.dto.ReportResult;
import org.gepnic.doors.masterapi.repository.ApiClientRepository; // 🚀 INJECTED
import org.gepnic.doors.masterapi.service.ReportViewerService;
import org.gepnic.doors.masterapi.util.EncryptionUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

@Slf4j
@RestController
@RequestMapping("/api/v1/master/gateway")
@RequiredArgsConstructor
public class ExternalGatewayController {

    private final ReportViewerService reportService;
    private final ApiClientRepository apiClientRepository; // 🚀 DB Connection for dynamic flag check
    private final ObjectMapper mapper = new ObjectMapper();

    @PostMapping("/orchestrate/{uniqueName}")
    public ResponseEntity<?> proxyOrchestration(
            @PathVariable String uniqueName,
            @RequestHeader("X-API-KEY") String apiKey,
            @RequestBody Map<String, Object> payload) {

        // 1. Resolve Identity from Security Context
        String authenticatedClient = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) payload.get("params");
            
            @SuppressWarnings("unchecked")
            List<String> agentIds = (List<String>) payload.get("agentIds");

            // 2. Build Request DTO
            ReportExecutionRequest request = ReportExecutionRequest.builder()
                    .queryUniqueName(uniqueName)
                    .agentId(agentIds != null ? String.join(",", agentIds) : "ALL")
                    .performedBy(authenticatedClient)
                    .params(params)
                    .build();

            // 3. Execute via Service
            ReportResult result = reportService.executeReport(request);

            // 4. Data Extraction & Pre-processing (Unwrapping JSON values)
            List<Map<String, Object>> resultList = result.data();
            
            if (resultList != null) {
                for (Map<String, Object> row : resultList) {
                    row.remove("null");
                    row.remove(null);
                    
                    if ("json".equals(row.get("type")) && row.get("value") instanceof String) {
                        try {
                            String rawJson = (String) row.get("value");
                            Object parsedJson = mapper.readValue(rawJson, Object.class);
                            row.put("value", parsedJson);
                        } catch (Exception e) {
                            log.warn("Failed to parse nested JSON for row, leaving as string.");
                        }
                    }
                }
            }

            List<String> offline = result.offlineAgents();
            boolean hasData = resultList != null && !resultList.isEmpty();
            boolean hasOffline = offline != null && !offline.isEmpty();

            // 5. Handle Total Failure
            if (!hasData && hasOffline) {
                String errorMsg = (offline.size() == 1) 
                        ? "Remote agent [" + offline.get(0) + "] is Offline" 
                        : "Multiple agents are Offline: " + String.join(", ", offline);
                
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(ApiResponse.error(errorMsg, 503));
            }

            // 🚀 DYNAMIC CHECK: Is encryption turned on for this specific client?
            // Defaults to secure (true) if client profile configuration cannot be resolved.
            boolean isEncryptionEnabled = apiClientRepository
                    .checkEncryptionStatusByApiKey(apiKey)
                    .orElse(true);

            // 6. Handle JSON Aggregation Format (Legacy Compact Mode)
            if (hasData && resultList.get(0).containsKey("json_agg")) {
                Object rawJson = resultList.get(0).get("json_agg");
                String jsonOutput = (rawJson != null) ? rawJson.toString() : "[]";

                // If encryption is disabled, bypass structural wrap and send plain text
                if (!isEncryptionEnabled) {
                    return ResponseEntity.ok()
                            .header("X-Content-Secure", "false")
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body(jsonOutput);
                }

                // If encryption is enabled, encrypt the legacy output string directly
                String encryptedLegacy = EncryptionUtils.encrypt(jsonOutput, apiKey);
                return ResponseEntity.ok()
                        .header("X-Content-Secure", "true")
                        .header("X-Key-Strategy", "API-Key-Segment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                            "secureData", encryptedLegacy,
                            "info", "Legacy Aggregation payload encrypted using API-Key Segment."
                        ));
            }

            // 7. DATA GOVERNANCE: Clean metadata before processing final output
            if (resultList != null) {
                resultList.forEach(row -> {
                    row.remove("NODE_ID"); 
                    row.remove("null"); 
                });
            }

            // 8. Final Response Assembly
            String msg = (!hasOffline) 
                ? "Orchestration successful" 
                : "Partial success. Unreachable nodes: " + String.join(", ", offline);

            ApiResponse<List<Map<String, Object>>> finalResponse = ApiResponse.success(resultList, msg);

            // 🚀 ONBOARDING BYPASS: Send standard plain-text JSON structure over secure HTTPS channel
            if (!isEncryptionEnabled) {
                log.info("DOORS-ONBOARDING: Shipping plain-text payload over HTTPS to client [{}]", authenticatedClient);
                return ResponseEntity.ok()
                        .header("X-Content-Secure", "false")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(finalResponse);
            }

            try {
                // 9. STANDARD MODE: Perform Dynamic AES-ECB Encryption
                String jsonResponse = mapper.writeValueAsString(finalResponse);
                String encryptedPayload = EncryptionUtils.encrypt(jsonResponse, apiKey);

                log.info("DOORS-SECURITY: Payload encrypted for Query [{}] by client [{}]", uniqueName, authenticatedClient);

                return ResponseEntity.ok()
                        .header("X-Content-Secure", "true")
                        .header("X-Key-Strategy", "API-Key-Segment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                            "secureData", encryptedPayload,
                            "info", "This payload is encrypted. Decrypt using provided SDK or AES logic."
                        ));

            } catch (Exception encryptEx) {
                log.error("Encryption Failure: Falling back to plain JSON for client [{}]", authenticatedClient, encryptEx);
                return ResponseEntity.ok(finalResponse);
            }

        } catch (Exception e) {
            log.error("DOORS-GATEWAY-ERROR: Failure for query [{}]", uniqueName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Internal Processing Error: " + e.getMessage(), 500));
        }
    }
}