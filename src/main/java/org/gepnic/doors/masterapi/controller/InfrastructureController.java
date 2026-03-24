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
    public ResponseEntity<ApiResponse<List<Agent>>> getAllAgents() {
        List<Agent> agents = agentRepository.findAll();
        return ResponseEntity.ok(ApiResponse.success(agents, "Agent list retrieved"));
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Agent>> registerAgent(@RequestBody Agent agent) {
        agent.setIsActive(true);
        agent.setCreatedAt(LocalDateTime.now());
        agent.setUpdatedAt(LocalDateTime.now());
        
        // Audit tracking for the infrastructure registry
        if (agent.getCreatedBy() == null) agent.setCreatedBy("GANAPATHI");
        if (agent.getUpdatedBy() == null) agent.setUpdatedBy("GANAPATHI");

        Agent savedAgent = agentRepository.save(agent);
        log.info("DOORS-MASTER: Registered new campus agent: {}", savedAgent.getAgentId());
        return ResponseEntity.ok(ApiResponse.success(savedAgent, "Agent registered successfully"));
    }
    @PutMapping("/update/{agentId}")
public ResponseEntity<ApiResponse<Agent>> updateAgent(
        @PathVariable String agentId, 
        @RequestBody Agent agentDetails,
        java.security.Principal principal) { // Inject Principal to get logged-in user
    
    // Resolve identity dynamically
    String currentUsername = (principal != null) ? principal.getName() : "SYSTEM";
    
    log.info("DOORS-MASTER: Configuration update for agent [{}] by user [{}]", agentId, currentUsername);
    
    return agentRepository.findById(agentId).map(existingAgent -> {
        // 1. Map Infrastructure Details
        existingAgent.setDisplayName(agentDetails.getDisplayName());
        existingAgent.setBaseUrl(agentDetails.getBaseUrl());
        existingAgent.setAgentInstanceCode(agentDetails.getAgentInstanceCode());
        
        // 2. Map Sandbox Flag (The missing field fix)
        existingAgent.setIsSandbox(agentDetails.getIsSandbox());
        
        // 3. Dynamic Auditing
        existingAgent.setUpdatedAt(LocalDateTime.now());
        existingAgent.setUpdatedBy(currentUsername); 

        // 4. Persistence
        Agent updated = agentRepository.save(existingAgent);
        
        log.info("DOORS-MASTER: Successfully updated agent [{}]", agentId);
        return ResponseEntity.ok(ApiResponse.success(updated, "Agent configuration updated successfully"));
        
    }).orElseGet(() -> {
        log.warn("DOORS-MASTER: Update failed. Agent [{}] not found", agentId);
        return ResponseEntity.status(404)
            .body(ApiResponse.error("Agent not found with ID: " + agentId, 404));
    });
}
    /* 
@PutMapping("/update/{agentId}")
    public ResponseEntity<ApiResponse<Agent>> updateAgent(
            @PathVariable String agentId, 
            @RequestBody Agent agentDetails) {
        
        log.info("DOORS-MASTER: Updating configuration for agent: {}", agentId);
        
        return agentRepository.findById(agentId).map(existingAgent -> {
            existingAgent.setDisplayName(agentDetails.getDisplayName());
            existingAgent.setBaseUrl(agentDetails.getBaseUrl());
            existingAgent.setAgentInstanceCode(agentDetails.getAgentInstanceCode());
            
            existingAgent.setUpdatedAt(LocalDateTime.now());
            existingAgent.setUpdatedBy("GANAPATHI"); // Or get from SecurityContext

            Agent updated = agentRepository.save(existingAgent);
            return ResponseEntity.ok(ApiResponse.success(updated, "Agent configuration updated"));
        }).orElseGet(() -> ResponseEntity.status(404)
            .body(ApiResponse.error("Agent not found with ID: " + agentId, 404)));
    }
    */
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

    /**
     * Endpoint to support Sidebar Status Badges.
     * Checks the sql_templates table for pending requests.
     */
    @GetMapping("/templates/count")
    public ResponseEntity<ApiResponse<Long>> getPendingCount(@RequestParam String status) {
        long count = sqlTemplateRepository.countByStatus(status); 
        log.info("DOORS-MASTER: Pending templates count requested: {}", count);
        return ResponseEntity.ok(ApiResponse.success(count, "Count retrieved"));
    }
@GetMapping("/ping")
public ResponseEntity<ApiResponse<Map<String, Object>>> pingAgent(@RequestParam("url") String targetUrl) {
    // We now take the 'url' from the query parameter sent by infrastructure.vue
    log.info("DOORS-MASTER: Executing Application Health Check for: {}", targetUrl);

    try {
        // 1. Target the new status endpoint on the agent side
        String healthCheckEndpoint = targetUrl.endsWith("/") ? targetUrl + "status" : targetUrl + "/status";

        // 2. Execute the check with a 5-second timeout
        Map<String, Object> agentResponse = webClientBuilder.build()
                .get()
                .uri(healthCheckEndpoint)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> Mono.error(new RuntimeException("Agent app returned error status")))
                .bodyToMono(Map.class) // We capture the JSON body (app status, db status, etc.)
                .timeout(java.time.Duration.ofSeconds(5)) 
                .block();

        Map<String, Object> data = new HashMap<>();
        data.put("online", true);
        data.put("details", agentResponse); // Pass through the agent's internal health details
        
        return ResponseEntity.ok(ApiResponse.success(data, "ONLINE"));

    } catch (Exception e) {
        log.error("DOORS-MASTER: Health Check Failed for {}: {}", targetUrl, e.getMessage());
        
        Map<String, Object> data = new HashMap<>();
        data.put("online", false);
        data.put("error", e.getMessage());
        
        return ResponseEntity.ok(ApiResponse.success(data, "OFFLINE"));
    }
}
/*  
@GetMapping("/ping/{agentId}")
public ResponseEntity<ApiResponse<Map<String, Object>>> pingAgent(@PathVariable String agentId) {
    Agent agent = agentRepository.findById(agentId)
            .orElseThrow(() -> new RuntimeException("Agent not found"));

    String targetUrl = agent.getBaseUrl();
    log.info("DOORS-MASTER: Pinging Agent {} at {}", agentId, targetUrl);

    try {
        // block() converts the reactive call to a normal synchronous response
        // This ensures the SecurityContext (ADMIN role) stays attached to the thread
        webClientBuilder.build()
                .get()
                .uri(targetUrl + "/api/v1/agent/test?sql=SELECT 1")
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> Mono.empty()) 
                .toBodilessEntity()
                .block(); // Wait for result synchronously

        Map<String, Object> data = new HashMap<>();
        data.put("online", true);
        return ResponseEntity.ok(ApiResponse.success(data, "ONLINE"));

    } catch (Exception e) {
        log.error("DOORS-MASTER: Ping failed for {}: {}", agentId, e.getMessage());
        Map<String, Object> data = new HashMap<>();
        data.put("online", false);
        return ResponseEntity.ok(ApiResponse.success(data, "OFFLINE"));
    }
}
*/
/* 
    @GetMapping("/ping/{agentId}")
    public Mono<ApiResponse<Map<String, Object>>> pingAgent(@PathVariable String agentId) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new RuntimeException("Agent not found"));

        String targetUrl = agent.getBaseUrl();
        log.info("DOORS-MASTER: Pinging Agent {} at {}", agentId, targetUrl);

        return webClientBuilder.build()
                .get()
                .uri(targetUrl + "/api/v1/agent/test?sql=SELECT 1")
                .retrieve()
                // Suppress errors to ensure "Online" status if any response is received
                .onStatus(HttpStatusCode::isError, response -> Mono.empty()) 
                .toBodilessEntity()
                .map(response -> {
                    Map<String, Object> data = new HashMap<>();
                    data.put("online", true);
                    return ApiResponse.success(data, "ONLINE");
                })
                .onErrorResume(e -> {
                    log.error("DOORS-MASTER: Ping failed for {}: {}", agentId, e.getMessage());
                    Map<String, Object> data = new HashMap<>();
                    data.put("online", false);
                    return Mono.just(ApiResponse.success(data, "OFFLINE"));
                });
    }
*/
    private boolean isPrivate(String url) {
        return url.contains("10.") || url.contains("172.16.") || url.contains("192.168.");
    }
}