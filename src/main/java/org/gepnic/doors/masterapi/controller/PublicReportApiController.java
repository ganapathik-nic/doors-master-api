package org.gepnic.doors.masterapi.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/master/reports")
@RequiredArgsConstructor
public class PublicReportApiController {
    private final ExternalGatewayController gateway;

    @PostMapping("/orchestrate/{uniqueName}")
    public ResponseEntity<?> orchestrateApiCall(@PathVariable String uniqueName,
            @RequestHeader("X-API-KEY") String apiKey,
            @RequestBody Map<String, Object> body, HttpServletRequest request) throws Exception {
        return gateway.proxyOrchestration(uniqueName, apiKey, body, request);
    }
}
