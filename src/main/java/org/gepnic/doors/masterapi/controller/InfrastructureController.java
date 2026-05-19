package org.gepnic.doors.masterapi.controller;

import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.model.Agent;
import org.gepnic.doors.masterapi.repository.AgentRepository;
import org.gepnic.doors.masterapi.repository.SqlTemplateRepository;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/master/infrastructure")
public class InfrastructureController {

    private final AgentRepository agentRepository;
    private final SqlTemplateRepository sqlTemplateRepository;
    private final WebClient.Builder webClientBuilder;

    /**
     * Constructor injection for required repositories and builders.
     * Ensures all dependencies are available for the 50-member team's operations.
     */
    public InfrastructureController(AgentRepository agentRepository, 
                                    SqlTemplateRepository sqlTemplateRepository, 
                                    WebClient.Builder webClientBuilder) {
        this.agentRepository = agentRepository;
        this.sqlTemplateRepository = sqlTemplateRepository;
        this.webClientBuilder = webClientBuilder;
    }

    @GetMapping("/agents/list")
    public ResponseEntity<ApiResponse<List<Agent>>> getActiveAgents() {
        List<Agent> activeAgents = agentRepository.findByIsActiveTrue();
        return ResponseEntity.ok(ApiResponse.success(activeAgents, "Active nodes retrieved"));
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Agent>> registerAgent(@RequestBody Agent agent) {
        agent.setIsActive(true);
        agent.setCreatedAt(LocalDateTime.now());
        agent.setUpdatedAt(LocalDateTime.now());
        
        // Audit tracking for the infrastructure registry
        if (agent.getCreatedBy() == null) agent.setCreatedBy("GANAPATHI");
        if (agent.getUpdatedBy() == null) agent.setUpdatedBy("GANAPATHI");

        // Explicit structural model default protection
        if (agent.getAgentType() == null) agent.setAgentType("INDIVIDUAL");

        Agent savedAgent = agentRepository.save(agent);
        log.info("DOORS-MASTER: Registered new hybrid agent [{}], Model Architecture Type: {}", 
                savedAgent.getAgentId(), savedAgent.getAgentType());
        return ResponseEntity.ok(ApiResponse.success(savedAgent, "Agent registered successfully"));
    }

    @PutMapping("/update/{agentId}")
    public ResponseEntity<ApiResponse<Agent>> updateAgent(
            @PathVariable String agentId, 
            @RequestBody Agent agentDetails,
            java.security.Principal principal) {
        
        // Resolve identity dynamically from security context session
        String currentUsername = (principal != null) ? principal.getName() : "SYSTEM";
        
        log.info("DOORS-MASTER: Configuration update for agent [{}] by user [{}]", agentId, currentUsername);
        
        return agentRepository.findById(agentId).map(existingAgent -> {
            // 1. Map Core Infrastructure Details
            existingAgent.setDisplayName(agentDetails.getDisplayName());
            existingAgent.setBaseUrl(agentDetails.getBaseUrl());
            existingAgent.setAgentInstanceCode(agentDetails.getAgentInstanceCode());
            existingAgent.setIsSandbox(agentDetails.getIsSandbox());
            
            // 2. Map New Hybrid Model Properties explicitly
            existingAgent.setAgentType(agentDetails.getAgentType() != null ? agentDetails.getAgentType() : "INDIVIDUAL");
            existingAgent.setTargetDbHost(agentDetails.getTargetDbHost());
            existingAgent.setTargetDbPort(agentDetails.getTargetDbPort());
            existingAgent.setTargetDbName(agentDetails.getTargetDbName());
            existingAgent.setTargetDbUser(agentDetails.getTargetDbUser());
            existingAgent.setTargetDbPassword(agentDetails.getTargetDbPassword());
            
            // 3. Dynamic Auditing
            existingAgent.setUpdatedAt(LocalDateTime.now());
            existingAgent.setUpdatedBy(currentUsername); 

            // 4. Persistence Execution
            Agent updated = agentRepository.save(existingAgent);
            
            log.info("DOORS-MASTER: Successfully updated configuration fields for hybrid agent [{}]", agentId);
            return ResponseEntity.ok(ApiResponse.success(updated, "Agent configuration updated successfully"));
            
        }).orElseGet(() -> {
            log.warn("DOORS-MASTER: Update failed. Agent [{}] not found", agentId);
            return ResponseEntity.status(404)
                .body(ApiResponse.error("Agent not found with ID: " + agentId, 404));
        });
    }

    @PutMapping("/deactivate/{agentId}")
    public ResponseEntity<ApiResponse<String>> deactivate(@PathVariable String agentId) {
        Agent agent = agentRepository.findById(agentId).orElseThrow();
        agent.setIsActive(false);
        agent.setUpdatedAt(LocalDateTime.now());
        agent.setUpdatedBy("GANAPATHI");
        agentRepository.save(agent);
        return ResponseEntity.ok(ApiResponse.success(null, "Deactivated"));
    }

    @PutMapping("/restore/{agentId}")
    public ResponseEntity<ApiResponse<String>> restore(@PathVariable String agentId) {
        Agent agent = agentRepository.findById(agentId).orElseThrow();
        agent.setIsActive(true);
        agent.setUpdatedAt(LocalDateTime.now());
        agent.setUpdatedBy("GANAPATHI");
        agentRepository.save(agent);
        return ResponseEntity.ok(ApiResponse.success(null, "Restored"));
    }

    @GetMapping("/templates/count")
    public ResponseEntity<ApiResponse<Long>> getPendingCount(@RequestParam String status) {
        long count = sqlTemplateRepository.countByStatus(status); 
        log.info("DOORS-MASTER: Pending templates count requested: {}", count);
        return ResponseEntity.ok(ApiResponse.success(count, "Count retrieved"));
    }
  @GetMapping("/ping")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pingAgent(
            @RequestParam(value = "url", required = false) String targetUrl) {
        
        if (targetUrl == null || targetUrl.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Query parameter 'url' is required", 400));
        }

        log.info("DOORS-MASTER: Parsing targeted infrastructure health routing path for: {}", targetUrl);

        try {
            // Normalize layout boundaries by removing terminal slashes
            String normalized = targetUrl.trim();
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }

            int protocolHeaderIndex = normalized.contains("://") ? normalized.indexOf("://") + 3 : 0;
            int firstPathSlashIndex = normalized.indexOf("/", protocolHeaderIndex);
            
            String healthCheckEndpoint;

            // 🚀 STRATEGY ROUTER EVALUATION
            if (normalized.contains("/doorsagent")) {
                // RULE 1: Production Reverse-Proxy Context Route
                // e.g., "https://demoetenders.tn.nic.in/doorsagent/staging-2" -> "https://demoetenders.tn.nic.in/doorsagent/"
                int doorsAgentIndex = normalized.indexOf("/doorsagent");
                healthCheckEndpoint = normalized.substring(0, doorsAgentIndex + "/doorsagent".length()) + "/";
            } else if (firstPathSlashIndex != -1) {
                // RULE 2: Local Standalone Development Origin
                // e.g., "http://127.0.0.1:8051/dev-01" -> "http://127.0.0.1:8051/"
                healthCheckEndpoint = normalized.substring(0, firstPathSlashIndex) + "/";
            } else {
                // Flat Host Fallback
                healthCheckEndpoint = normalized + "/";
            }

            log.info("DOORS-MASTER: Executing network probe. Targeted Ping Endpoint: {}", healthCheckEndpoint);

            // 🛡️ REDIRECT-AWARE EXPLICIT VITALITY CHECK
            // We strip followRedirect capabilities to isolate 302 masquerading anomalies
            Integer statusCode = webClientBuilder
                    .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                            reactor.netty.http.client.HttpClient.create().followRedirect(false)
                    ))
                    .build()
                    .get()
                    .uri(healthCheckEndpoint)
                    .exchangeToMono(response -> Mono.just(response.statusCode().value()))
                    .timeout(java.time.Duration.ofSeconds(4))
                    .block();

            log.info("DOORS-MASTER: Gateway Probe Response Code collected: {}", statusCode);

            // ⚡ DECISION ENGINE:
            // A node is healthy only if it responds directly without a 302 gateway detour, 
            // and doesn't drop a 5xx error (502 Bad Gateway/504 Timeout) indicating a stopped backend process.
            boolean isAlive = (statusCode != null && statusCode != 302 && statusCode < 500);

            Map<String, Object> data = new HashMap<>();
            data.put("online", isAlive);
            
            Map<String, Object> details = new HashMap<>();
            details.put("httpStatusCode", statusCode);
            details.put("evaluatedPingPath", healthCheckEndpoint);
            data.put("details", details);
            
            String operationalStatus = isAlive ? "ONLINE" : "OFFLINE";
            return ResponseEntity.ok(ApiResponse.success(data, operationalStatus));

        } catch (Exception e) {
            log.error("DOORS-MASTER: Ping connection failed for [{}]. Error Context: {}", targetUrl, e.getMessage());
            
            Map<String, Object> data = new HashMap<>();
            data.put("online", false);
            
            Map<String, Object> details = new HashMap<>();
            details.put("httpStatusCode", 0);
            details.put("evaluatedPingPath", targetUrl);
            details.put("exceptionMessage", e.getMessage());
            data.put("details", details);
            
            return ResponseEntity.ok(ApiResponse.success(data, "OFFLINE"));
        }
    }

}