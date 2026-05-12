package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.dto.ReportExecutionRequest;
import org.gepnic.doors.masterapi.dto.ReportResult;
import org.gepnic.doors.masterapi.service.ReportViewerService;
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

            // 🛡️ 4. Extract Data and Offline Agents with Null-Safety
            List<Map<String, Object>> resultList = result.data();
            ObjectMapper mapper = new ObjectMapper();
            if (resultList != null) {
    resultList.forEach(row -> {
        row.remove("null");
        row.remove(null); // Just in case it's a literal null key
    });
    if (resultList != null) {
    for (Map<String, Object> row : resultList) {
        row.remove("null");
        
        // 🚀 THE UNWRAPPER: If type is 'json' and value is a String, parse it!
        if ("json".equals(row.get("type")) && row.get("value") instanceof String) {
            try {
                String rawJson = (String) row.get("value");
                // Convert the escaped string into a real JSON Object/Map
                Object parsedJson = mapper.readValue(rawJson, Object.class);
                row.put("value", parsedJson);
            } catch (Exception e) {
                log.warn("Failed to parse nested JSON for row, leaving as string.");
            }
        }
    }
}
}
            List<String> offline = result.offlineAgents();

            boolean hasData = resultList != null && !resultList.isEmpty();
            boolean hasOffline = offline != null && !offline.isEmpty();

            // 🛡️ 5. Handle Total Failure (All chosen nodes offline)
            if (!hasData && hasOffline) {
                String errorMsg = (offline.size() == 1) 
                    ? "Remote agent [" + offline.get(0) + "] is Offline" 
                    : "Multiple agents are Offline: " + String.join(", ", offline);
                
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(ApiResponse.error(errorMsg, 503));
            }

            // 🛡️ 6. SMART COMPACT FORMATTING (Original Logic - Adapted for resultList)
            if (hasData && resultList.get(0).containsKey("json_agg")) {
                Object rawJson = resultList.get(0).get("json_agg");
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

            // 🛡️ 7. Standard Fallback: Extract the LIST only
            String msg = (!hasOffline) 
                ? "Orchestration successful" 
                : "Partial success. Unreachable nodes: " + String.join(", ", offline);

            return ResponseEntity.ok(ApiResponse.success(resultList, msg));

        } catch (SecurityException se) {
            log.warn("🚨 GATEWAY SECURITY BLOCK: Client [{}] sent malicious payload.", authenticatedClient);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(se.getMessage(), 403));
        } catch (Exception e) {
            log.error("DOORS-GATEWAY-ERROR: Failure for query [{}]", uniqueName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Internal Processing Error: " + e.getMessage(), 500));
        }
    }
}