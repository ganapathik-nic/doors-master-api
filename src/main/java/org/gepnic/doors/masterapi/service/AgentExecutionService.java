package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.model.Agent;
import org.gepnic.doors.masterapi.model.SqlTemplate;
import org.gepnic.doors.masterapi.model.User;
import org.gepnic.doors.masterapi.repository.AgentRepository;
import org.gepnic.doors.masterapi.repository.UserRepository;
import org.gepnic.doors.masterapi.util.EncryptionUtils;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.JdbcTemplate; 
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate; 
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutionService {

    private final AgentRepository agentRepository;
    private final UserRepository userRepository;
    private final WebClient.Builder webClientBuilder;
    private final JdbcTemplate jdbcTemplate; 
    private final RestTemplate restTemplate; 

    private static final Pattern SQL_INJECTION_PATTERN = Pattern.compile(
        "(?i)(--|;|\\bUNION\\b|\\bSELECT\\b|\\bDROP\\b|\\bOR\\b\\s+\\d+=\\d+|\\bUPDATE\\b|\\bDELETE\\b)", 
        Pattern.CASE_INSENSITIVE
    );

    public List<String> getAuthorizedExecutionTargets(SqlTemplate template) {
        String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();
        
        // Securely fetch authorized agents for the user
        String authSql = "SELECT agent_id FROM user_authorized_agents WHERE user_name ILIKE ?";
        List<String> userAuthorizedAgents = jdbcTemplate.queryForList(authSql, String.class, currentUsername);

        List<String> queryAllowedAgents = template.getAuthorizedAgents();

        return queryAllowedAgents.stream()
                .filter(userAuthorizedAgents::contains)
                .collect(Collectors.toList());
    }

    public Map<String, Object> executeDryRun(String agentId, String base64Payload, Map<String, Object> params) {
        // 1. Fetch target agent configuration row from your JPA repository registry
        Agent agent = agentRepository.findById(agentId.trim())
                .orElseThrow(() -> new NoSuchElementException("Agent record missing for ID: " + agentId));

        // 2. Normalize base url strings
        String rawBaseUrl = agent.getBaseUrl() != null ? agent.getBaseUrl().trim() : "";
        while (rawBaseUrl.endsWith("/")) {
            rawBaseUrl = rawBaseUrl.substring(0, rawBaseUrl.length() - 1);
        }

        // 3. 🚀 THE ARCHITECTURE REFACTOR ROUTER
        int lastSlashIndex = rawBaseUrl.lastIndexOf("/");
        if (lastSlashIndex == -1 || lastSlashIndex < rawBaseUrl.indexOf("://") + 3) {
            throw new IllegalArgumentException("Malformed base_url in registry for agent: " + agentId);
        }

        // e.g., "dev-02" or "assam"
        String extractedInstanceCode = rawBaseUrl.substring(lastSlashIndex + 1); 
        
        // e.g., "http://127.0.0.1:8051" or "https://demoetenders.tn.nic.in/doorsagent"
        String proxyNetworkRoot = rawBaseUrl.substring(0, lastSlashIndex); 

        // 🎯 THE CRITICAL ALIGNMENT FIX: Target the raw, unslashed root endpoint configuration
        String endpoint = proxyNetworkRoot + "/v1/agent/query/dry-run";

        log.info("DOORS-GOVERNANCE: Running dry-run validation routing against endpoint -> {}", endpoint);

        // 4. Build outbound payload envelope frame matching your Agent requirements
        Map<String, Object> agentPayload = new HashMap<>();
        
        // Decode Base64 string safely before handoff to unpooled agent
        String decodedSql = "";
        if (base64Payload != null && !base64Payload.isBlank()) {
            decodedSql = new String(Base64.getDecoder().decode(base64Payload.trim()));
        }
        agentPayload.put("sql", decodedSql);
        agentPayload.put("params", params != null ? params : new HashMap<>());
        agentPayload.put("instanceCode", extractedInstanceCode);

        // Inject target database credentials dynamically from the matching repository record row
        agentPayload.put("dbHost", agent.getTargetDbHost() != null ? agent.getTargetDbHost().trim() : "");
        agentPayload.put("dbPort", agent.getTargetDbPort());
        agentPayload.put("dbName", agent.getTargetDbName() != null ? agent.getTargetDbName().trim() : "");
        agentPayload.put("dbUser", agent.getTargetDbUser() != null ? agent.getTargetDbUser().trim() : "");
        
        // 5. 🛡️ Dispatch payload inside the try-catch block to handle checked exceptions gracefully
        try {
            // 🚀 THE FIX: Checked exception source is now safely wrapped inside the error handler frame
            if (agent.getTargetDbPassword() != null && !agent.getTargetDbPassword().isBlank()) {
                String encryptedPass = EncryptionUtils.encrypt(agent.getTargetDbPassword().trim(), "DOORS_VLAN_INTERNAL_SECRET_KEY_2026");
                agentPayload.put("dbPasswordSecure", encryptedPass);
            } else {
                agentPayload.put("dbPasswordSecure", "");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(endpoint, agentPayload, Map.class);
            return response != null ? response : new HashMap<>();
        } catch (Exception ex) {
            log.error("DOORS-GOVERNANCE: Proxy handoff failure or encryption fault on route [{}]: {}", endpoint, ex.getMessage());
            throw new RuntimeException(ex.getMessage(), ex); // Transform checked exception to unmanaged RuntimeException
        }
    }

    private void validateParams(Map<String, Object> params) {
        if (params == null) return;
        for (Object value : params.values()) {
            if (value instanceof String strValue) {
                if (SQL_INJECTION_PATTERN.matcher(strValue).find()) {
                    throw new SecurityException("Security Violation: Malicious patterns detected in input.");
                }
            }
        }
    }
}