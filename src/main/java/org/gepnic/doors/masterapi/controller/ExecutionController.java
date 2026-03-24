package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.service.QueryExecutionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

// FIX: Added the missing Map import
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;

@RestController
@RequestMapping("/api/v1/master/execution")
@RequiredArgsConstructor
public class ExecutionController {

    private final QueryExecutionService executionService;

    /**
     * MAIN REPORT ENDPOINT (GET)
     * Updated to provide an empty Map to satisfy the 4-argument service requirement.
     */
    @GetMapping(value = "/run", produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<Object> executeReport(
            @RequestParam String agentId,
            @RequestParam String queryName,
            @RequestParam String userId) {

        // FIX: Added Collections.emptyMap() to match the new Service signature
        return executionService.executeRemote(agentId, queryName, userId, Collections.emptyMap());
    }

    /**
     * DYNAMIC ORCHESTRATION ENDPOINT (POST)
     */
    @PostMapping("/execute")
    public Flux<Object> execute(@RequestBody Map<String, Object> payload) {
        String agentId = (String) payload.get("agentId");
        String queryName = (String) payload.get("queryName");
        String userId = (String) payload.getOrDefault("userId", "SYSTEM"); 
        
        // Safely extract params
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) payload.getOrDefault("params", new HashMap<>());

        return executionService.executeRemote(agentId, queryName, userId, params);
    }
}