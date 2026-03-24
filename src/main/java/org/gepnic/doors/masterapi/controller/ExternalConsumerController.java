package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j; // Required for 'log'
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.repository.ClientQueryMapRepository;
import org.gepnic.doors.masterapi.repository.ApiClientRepository;
import org.gepnic.doors.masterapi.repository.SqlTemplateRepository; // Fixed
import org.gepnic.doors.masterapi.service.QueryExecutionService;   // Fixed
import org.gepnic.doors.masterapi.model.SqlTemplate;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList; // Required for ArrayList
import java.util.List;      // Required for List
import java.util.Map;       // Required for Map

@Slf4j // This fixes the "log cannot be resolved" error
@RestController
@RequestMapping("/api/v1/external")
@RequiredArgsConstructor
//@CrossOrigin(origins = "*", allowedHeaders = {"X-API-KEY", "Content-Type", "Authorization"})
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class ExternalConsumerController {

    private final ClientQueryMapRepository mappingRepository;
    private final ApiClientRepository apiClientRepository;
    private final SqlTemplateRepository sqlTemplateRepository; // Fixed
    private final QueryExecutionService executionService;       // Fixed

    @PostMapping("/execute/{queryId}")
    public ResponseEntity<Object> executeAuthorizedQuery(
            @PathVariable Long queryId,
            HttpServletRequest request) {

        // 1. Authenticate & Authorize Client
        String apiKey = request.getHeader("X-API-KEY");
        ApiClient client = apiClientRepository.findByApiKey(apiKey)
                .orElse(null);

        if (client == null || !mappingRepository.existsByClientIdAndQueryId(client.getClientId(), queryId)) {
            return ResponseEntity.status(403)
                .body(ApiResponse.error("Access Denied: Unmapped query or invalid key.", 403));
        }

        // 2. Fetch Template & Its Authorized Agents
        SqlTemplate template = sqlTemplateRepository.findById(queryId)
                .orElseThrow(() -> new RuntimeException("Template not found: " + queryId));

        List<String> agentIds = template.getAuthorizedAgents();
        if (agentIds == null || agentIds.isEmpty()) {
            return ResponseEntity.status(422)
                .body(ApiResponse.error("Configuration Error: No agents mapped to this query.", 422));
        }

        // 3. FAN-OUT EXECUTION
        List<Map<String, Object>> aggregatedResults = new ArrayList<>();
        
        for (String agentId : agentIds) {
            try {
                // Using your existing fetchFromAgent logic from QueryExecutionService
                List<Map<String, Object>> agentData = executionService.fetchFromAgent(agentId, template.getUniqueName());
                if (agentData != null) {
                    // Add metadata so the client knows which agent provided which rows
                    for (Map<String, Object> row : agentData) {
                        row.put("_origin_agent", agentId);
                    }
                    aggregatedResults.addAll(agentData);
                }
            } catch (Exception e) {
                log.error("Failed to fetch from agent {}: {}", agentId, e.getMessage());
            }
        }

        return ResponseEntity.ok(ApiResponse.success(aggregatedResults, "Data aggregated from " + agentIds.size() + " agents."));
    }
}