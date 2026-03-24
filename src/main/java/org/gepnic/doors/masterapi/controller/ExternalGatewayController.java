package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.dto.ReportExecutionRequest;
import org.gepnic.doors.masterapi.service.ReportViewerService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Gateway for external system integrations.
 * Proxies calls to the Service Layer to avoid Controller-to-Controller coupling.
 */
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

        // 1. Get the actual Client Name (e.g., UPPTCL) from the Security Context
        String authenticatedClient = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getName();

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) payload.get("params");
        
        @SuppressWarnings("unchecked")
        List<String> agentIds = (List<String>) payload.get("agentIds");

        // 2. Map payload to Request DTO
        ReportExecutionRequest request = ReportExecutionRequest.builder()
                .queryUniqueName(uniqueName)
                .agentId(agentIds != null ? String.join(",", agentIds) : "ALL")
                .performedBy(authenticatedClient)
                .params(params)
                .build();

        // 3. Execute via Service
        List<Map<String, Object>> data = reportService.executeReport(request);

        // 4. SMART COMPACT FORMATTING
        // Check if the first row contains the 'json_agg' aggregate
         // 4. SMART COMPACT FORMATTING
        if (data != null && !data.isEmpty() && data.get(0).containsKey("json_agg")) {
            Object rawJson = data.get(0).get("json_agg");
            String jsonOutput = "";

            if (rawJson instanceof Map) {
                // Cast to a raw Map to bypass strict generic checks
                Map<?, ?> wrapperMap = (Map<?, ?>) rawJson;
                Object valueObj = wrapperMap.get("value");
                jsonOutput = (valueObj != null) ? valueObj.toString() : "[]";
            } else if (rawJson != null) {
                jsonOutput = rawJson.toString();
            }

            // Return Raw JSON String directly
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(jsonOutput);
        }
        // 5. Fallback for standard (non-json_agg) queries
        return ResponseEntity.ok(ApiResponse.success(data, "Orchestration successful"));
    }
}