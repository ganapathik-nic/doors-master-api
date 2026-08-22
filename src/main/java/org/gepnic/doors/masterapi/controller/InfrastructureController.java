package org.gepnic.doors.masterapi.controller;

import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.model.Agent;
import org.gepnic.doors.masterapi.repository.AgentRepository;
import org.gepnic.doors.masterapi.repository.SqlTemplateRepository;
import org.gepnic.doors.masterapi.util.EncryptionUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
    public ResponseEntity<ApiResponse<Map<String, Object>>> getActiveAgents(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        try {
            String hybridKey = buildHybridKey(authorizationHeader);
            List<Agent> activeAgents = agentRepository.findByIsActiveTrue();
            String serializedAgents = new com.fasterxml.jackson.databind.ObjectMapper()
                    .findAndRegisterModules()
                    .writeValueAsString(activeAgents);

            Map<String, Object> encryptedEnvelope = new HashMap<>();
            encryptedEnvelope.put("isEncryptedPayload", true);
            encryptedEnvelope.put(
                    "secureData",
                    EncryptionUtils.encrypt(serializedAgents, hybridKey)
            );
            encryptedEnvelope.put("rowCount", activeAgents.size());

            return ResponseEntity.ok(ApiResponse.success(
                    encryptedEnvelope,
                    "Active nodes retrieved securely"
            ));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(401).body(ApiResponse.error(
                    exception.getMessage(),
                    401
            ));
        } catch (Exception exception) {
            log.error("Unable to encrypt active agent registry", exception);
            return ResponseEntity.status(500).body(ApiResponse.error(
                    "Unable to secure the active agent registry response",
                    500
            ));
        }
    }

    private String buildHybridKey(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new IllegalArgumentException("Authorization token is required");
        }
        String rawJwt = authorizationHeader
                .replaceFirst("(?i)^Bearer\\s+", "")
                .trim();
        if (rawJwt.length() < 8) {
            throw new IllegalArgumentException("Authorization token is invalid");
        }
        return "D00RS-NI" + rawJwt.substring(rawJwt.length() - 8);
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Map<String, Object>>> registerAgent(@RequestBody Agent agent) {
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
        return ResponseEntity.ok(ApiResponse.success(
                toSafeAgentMetadata(savedAgent),
                "Agent registered successfully"
        ));
    }

    @PutMapping("/update/{agentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateAgent(
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
            if (hasText(agentDetails.getTargetDbHost())) {
                existingAgent.setTargetDbHost(agentDetails.getTargetDbHost().trim());
            }
            if (agentDetails.getTargetDbPort() != null) {
                existingAgent.setTargetDbPort(agentDetails.getTargetDbPort());
            }
            if (hasText(agentDetails.getTargetDbName())) {
                existingAgent.setTargetDbName(agentDetails.getTargetDbName().trim());
            }
            if (hasText(agentDetails.getTargetDbUser())) {
                existingAgent.setTargetDbUser(agentDetails.getTargetDbUser().trim());
            }
            if (hasText(agentDetails.getTargetDbPassword())) {
                existingAgent.setTargetDbPassword(agentDetails.getTargetDbPassword());
            }
            
            // 3. Dynamic Auditing
            existingAgent.setUpdatedAt(LocalDateTime.now());
            existingAgent.setUpdatedBy(currentUsername); 

            // 4. Persistence Execution
            Agent updated = agentRepository.save(existingAgent);
            
            log.info("DOORS-MASTER: Successfully updated configuration fields for hybrid agent [{}]", agentId);
            return ResponseEntity.ok(ApiResponse.success(
                    toSafeAgentMetadata(updated),
                    "Agent configuration updated successfully"
            ));
            
        }).orElseGet(() -> {
            log.warn("DOORS-MASTER: Update failed. Agent [{}] not found", agentId);
            return ResponseEntity.status(404)
                .body(ApiResponse.error("Agent not found with ID: " + agentId, 404));
        });
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Map<String, Object> toSafeAgentMetadata(Agent agent) {
        Map<String, Object> safe = new HashMap<>();
        safe.put("agentId", agent.getAgentId());
        safe.put("displayName", agent.getDisplayName());
        safe.put("baseUrl", agent.getBaseUrl());
        safe.put("agentInstanceCode", agent.getAgentInstanceCode());
        safe.put("agentType", agent.getAgentType());
        safe.put("isActive", agent.getIsActive());
        safe.put("isSandbox", agent.getIsSandbox());
        safe.put("status", agent.getStatus());
        safe.put("createdAt", agent.getCreatedAt());
        safe.put("createdBy", agent.getCreatedBy());
        safe.put("updatedAt", agent.getUpdatedAt());
        safe.put("updatedBy", agent.getUpdatedBy());
        safe.put(
                "databaseConfigured",
                hasText(agent.getTargetDbHost()) &&
                        agent.getTargetDbPort() != null &&
                        hasText(agent.getTargetDbName()) &&
                        hasText(agent.getTargetDbUser())
        );
        return safe;
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
    @GetMapping("/ping/{agentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pingRegisteredAgent(
            @PathVariable String agentId) {
        Agent agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error("Agent not found with ID: " + agentId, 404));
        }

        String healthEndpoint = buildHealthEndpoint(agent);
        String queryEndpoint = buildQueryEndpoint(agent);
        boolean databaseConfigured = hasText(agent.getTargetDbHost()) &&
                agent.getTargetDbPort() != null &&
                hasText(agent.getTargetDbName()) &&
                hasText(agent.getTargetDbUser());
        WebClient client = webClientBuilder
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                        reactor.netty.http.client.HttpClient.create().followRedirect(false)
                ))
                .build();

        boolean jvmOnline = false;
        boolean databaseOnline = false;
        boolean databaseChecked = false;
        int jvmStatusCode = 0;
        int databaseStatusCode = 0;
        String jvmMessage = null;
        String databaseMessage = databaseConfigured ? null : "Database profile is not configured";

        try {
            ProbeResult jvmResult = client.get()
                    .uri(healthEndpoint)
                    .exchangeToMono(response -> response.bodyToMono(Object.class)
                            .defaultIfEmpty(Map.of())
                            .map(body -> new ProbeResult(response.statusCode().value(), body)))
                    .timeout(java.time.Duration.ofSeconds(6))
                    .block();
            jvmStatusCode = jvmResult != null ? jvmResult.statusCode() : 0;
            Object body = jvmResult != null ? jvmResult.body() : null;
            jvmOnline = jvmStatusCode == 200 && !(body instanceof Map<?, ?> map &&
                    Boolean.FALSE.equals(map.get("online")));
            if (body instanceof Map<?, ?> map && map.get("message") != null) {
                jvmMessage = map.get("message").toString();
            }
        } catch (Exception exception) {
            jvmMessage = "Agent JVM probe failed: " + exception.getClass().getSimpleName();
            log.warn("DOORS-MASTER: JVM health probe failed for agent [{}] at [{}]: {}",
                    agentId, healthEndpoint, exception.getMessage());
        }

        // The routed execution endpoint is also a valid JVM liveness signal.
        // Some deployed reverse proxies expose /v1/agent/query/execute but not
        // the optional /doorsagent/status endpoint, which otherwise produces a
        // false OFFLINE/DISCONNECTED result.
        if (databaseConfigured) {
            databaseChecked = true;
            try {
                Map<String, Object> payload = new HashMap<>();
                // Connectivity health must not depend on an application table.
                // A missing/renamed gep_properites table previously marked a
                // working database as DISCONNECTED even though JDBC was healthy.
                payload.put("sql", "SELECT 1 AS health_count");
                payload.put("params", Map.of());
                payload.put("instanceCode", hasText(agent.getAgentInstanceCode())
                        ? agent.getAgentInstanceCode().trim()
                        : agent.getAgentId());
                payload.put("dbHost", agent.getTargetDbHost().trim());
                payload.put("dbPort", agent.getTargetDbPort());
                payload.put("dbName", agent.getTargetDbName().trim());
                payload.put("dbUser", agent.getTargetDbUser().trim());
                payload.put("dbPasswordSecure", hasText(agent.getTargetDbPassword())
                        ? EncryptionUtils.encrypt(agent.getTargetDbPassword().trim(),
                                "DOORS_VLAN_INTERNAL_SECRET_KEY_2026")
                        : "");

                ProbeResult databaseResult = client.post()
                        .uri(queryEndpoint)
                        .bodyValue(payload)
                        .exchangeToMono(response -> response.bodyToMono(Object.class)
                                .defaultIfEmpty(List.of())
                                .map(body -> new ProbeResult(response.statusCode().value(), body)))
                        .timeout(java.time.Duration.ofSeconds(8))
                        .block();
                databaseStatusCode = databaseResult != null ? databaseResult.statusCode() : 0;
                Object body = databaseResult != null ? databaseResult.body() : null;
                boolean legacyFailure = body instanceof List<?> rows && rows.stream()
                        .filter(Map.class::isInstance)
                        .map(Map.class::cast)
                        .anyMatch(row -> Boolean.FALSE.equals(row.get("success")) || row.get("error") != null);
                boolean legacySuccess = body instanceof List<?> rows && !rows.isEmpty() && !legacyFailure;
                boolean envelopeSuccess = body instanceof Map<?, ?> envelope &&
                        !Boolean.FALSE.equals(envelope.get("success")) &&
                        envelope.get("data") instanceof List<?> rows && !rows.isEmpty();
                databaseOnline = databaseStatusCode == 200 && (legacySuccess || envelopeSuccess);
                databaseMessage = databaseOnline
                        ? "Database connection verified through the interactive-report execution path"
                        : extractAgentDatabaseError(body);
                if (databaseOnline && !jvmOnline) {
                    jvmOnline = true;
                    jvmMessage = "Agent execution endpoint is online; dedicated status endpoint returned " +
                            (jvmStatusCode == 0 ? "no response" : "HTTP " + jvmStatusCode);
                }
            } catch (Exception exception) {
                databaseMessage = "Database query probe failed: " + exception.getClass().getSimpleName();
                log.warn("DOORS-MASTER: Database query probe failed for agent [{}] at [{}]: {}",
                        agentId, queryEndpoint, exception.getMessage());
            }
        }

        Map<String, Object> details = new HashMap<>();
        details.put("agentId", agentId);
        details.put("agentType", agent.getAgentType());
        details.put("jvmOnline", jvmOnline);
        details.put("jvmStatusCode", jvmStatusCode);
        details.put("jvmProbePath", healthEndpoint);
        details.put("jvmMessage", jvmMessage);
        details.put("databaseChecked", databaseChecked);
        details.put("databaseOnline", databaseOnline);
        details.put("databaseStatusCode", databaseStatusCode);
        details.put("databaseProbePath", queryEndpoint);
        details.put("databaseMessage", databaseMessage);
        details.put("message", databaseMessage != null ? databaseMessage : jvmMessage);

        Map<String, Object> data = new HashMap<>();
        data.put("online", jvmOnline && (!databaseConfigured || databaseOnline));
        data.put("details", details);
        return ResponseEntity.ok(ApiResponse.success(data,
                jvmOnline && (!databaseConfigured || databaseOnline) ? "ONLINE" : "DEGRADED"));
    }

    private String buildQueryEndpoint(Agent agent) {
        String healthEndpoint = buildHealthEndpoint(agent);
        return healthEndpoint.substring(0, healthEndpoint.length() - "/status".length()) +
                "/v1/agent/query/execute";
    }

    private String extractAgentDatabaseError(Object body) {
        if (body instanceof Map<?, ?> map) {
            Object error = map.get("error");
            if (error != null) return error.toString();
            Object message = map.get("message");
            if (message != null) return message.toString();
        }
        if (body instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) {
                    Object error = map.get("error");
                    if (error != null) return error.toString();
                    Object message = map.get("message");
                    if (message != null) return message.toString();
                }
            }
        }
        return "Database health query returned no successful result";
    }

    private String buildHealthEndpoint(Agent agent) {
        String baseUrl = hasText(agent.getBaseUrl()) ? agent.getBaseUrl().trim() : "";
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        URI uri = URI.create(baseUrl);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null ||
                uri.getFragment() != null ||
                !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Agent registry contains an invalid health destination");
        }

        String instanceCode = hasText(agent.getAgentInstanceCode())
                ? agent.getAgentInstanceCode().trim()
                : agent.getAgentId().trim();
        String lowerBaseUrl = baseUrl.toLowerCase(Locale.ROOT);
        int routedInstanceIndex = lowerBaseUrl.indexOf("/doorsagent/");
        if (routedInstanceIndex >= 0) {
            baseUrl = baseUrl.substring(0, routedInstanceIndex + "/doorsagent".length());
        }
        String instanceSuffix = "/" + instanceCode;
        if (baseUrl.toLowerCase(Locale.ROOT).endsWith(instanceSuffix.toLowerCase(Locale.ROOT))) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - instanceSuffix.length());
        }
        return baseUrl.toLowerCase(Locale.ROOT).endsWith("/doorsagent")
                ? baseUrl + "/status"
                : baseUrl + "/doorsagent/status";
    }

    private void validateCentralDatabaseProfile(Agent agent) {
        if (!hasText(agent.getTargetDbHost()) || agent.getTargetDbPort() == null ||
                agent.getTargetDbPort() < 1 || agent.getTargetDbPort() > 65535 ||
                !hasText(agent.getTargetDbName()) || !hasText(agent.getTargetDbUser())) {
            throw new IllegalArgumentException("CENTRAL agent database profile is incomplete");
        }
    }

    private record ProbeResult(int statusCode, Object body) {
    }

    // Kept temporarily as an internal compatibility helper; it has no HTTP mapping.
    private ResponseEntity<ApiResponse<Map<String, Object>>> legacyPingAgent(String targetUrl) {
        
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
