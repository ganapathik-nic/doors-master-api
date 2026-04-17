package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.dto.ReportExecutionRequest;
import org.gepnic.doors.masterapi.service.ReportViewerService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/master/gateway")
@RequiredArgsConstructor
public class ExternalGatewayController {

    private final ReportViewerService reportService;

    @PostMapping("/orchestrate/{uniqueName}")
    public ResponseEntity<?> proxyOrchestration(
            @PathVariable String uniqueName,
            @RequestHeader("X-API-KEY") String apiKey,
            @RequestBody Map<String, Object> payload) {

        // 1. Resolve Identity from Security Context (Hardened)
        String authenticatedClient = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) payload.get("params");
        
        @SuppressWarnings("unchecked")
        List<String> agentIds = (List<String>) payload.get("agentIds");

        // 2. Map payload to Request DTO (Includes params for scanning)
        ReportExecutionRequest request = ReportExecutionRequest.builder()
                .queryUniqueName(uniqueName)
                .agentId(agentIds != null ? String.join(",", agentIds) : "ALL")
                .performedBy(authenticatedClient)
                .params(params)
                .build();

        try {
            // 3. Execute via Service (Triggers Agent Validation & SQL Scan)
            List<Map<String, Object>> data = reportService.executeReport(request);

            // 4. SMART COMPACT FORMATTING (Original Logic)
            if (data != null && !data.isEmpty() && data.get(0).containsKey("json_agg")) {
                Object rawJson = data.get(0).get("json_agg");
                String jsonOutput = "";

                if (rawJson instanceof Map) {
                    Map<?, ?> wrapperMap = (Map<?, ?>) rawJson;
                    Object valueObj = wrapperMap.get("value");
                    jsonOutput = (valueObj != null) ? valueObj.toString() : "[]";
                } else if (rawJson != null) {
                    jsonOutput = rawJson.toString();
                }

                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(jsonOutput);
            }

            // 5. Standard Fallback
            return ResponseEntity.ok(ApiResponse.success(data, "Orchestration successful"));

        } catch (SecurityException se) {
            // 🛡️ CRITICAL: Catch the hardening trigger!
            log.warn("🚨 GATEWAY SECURITY BLOCK: Client [{}] sent malicious payload. Pattern caught.", authenticatedClient);
            return ResponseEntity.status(403)
                    .body(ApiResponse.error(se.getMessage(), 403));
        } catch (Exception e) {
            log.error("DOORS-GATEWAY-ERROR: Failure for query [{}]", uniqueName, e);
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("Internal Processing Error: " + e.getMessage(), 500));
        }
    }
}