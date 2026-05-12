package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.model.Agent;
import org.gepnic.doors.masterapi.model.SqlTemplate;
import org.gepnic.doors.masterapi.model.User;
import org.gepnic.doors.masterapi.repository.AgentRepository;
import org.gepnic.doors.masterapi.repository.UserRepository;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.JdbcTemplate; // 🛡️ CRITICAL: Added this missing import
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
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

    // In AgentExecutionService.java
 public Map<String, Object> executeDryRun(String agentId, String base64Sql, Map<String, Object> params) {
    String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();

    // 🛡️ 1. NULL GUARD: Prevent the "src is null" crash
    if (base64Sql == null || base64Sql.trim().isEmpty()) {
        throw new IllegalArgumentException("SQL payload (base64Sql) is missing or null");
    }

    // 🛡️ 2. Authorization Logic
    String checkMapping = "SELECT COUNT(*) FROM user_authorized_agents WHERE user_name ILIKE ? AND agent_id = ?";
    Integer count = jdbcTemplate.queryForObject(checkMapping, Integer.class, currentUsername, agentId);
    
    if (count == null || count == 0) {
        throw new SecurityException("Security Violation: Access Denied for agent " + agentId);
    }

    // 🛡️ 3. DECODE & SCRUB: Convert Base64 back to SQL and remove whitespace (Char 20 fix)
    String decodedSql;
    try {
        // Use MimeDecoder to be lenient with spaces/newlines in production
        byte[] decodedBytes = Base64.getMimeDecoder().decode(base64Sql.replaceAll("\\s", ""));
        decodedSql = new String(decodedBytes);
    } catch (Exception e) {
        log.error("DOORS-MASTER: Base64 Decoding failed for agent {}", agentId);
        throw new IllegalArgumentException("Invalid Base64 encoding in SQL payload");
    }

    // 4. Resolve Agent URL
    Agent agent = agentRepository.findById(agentId)
            .orElseThrow(() -> new RuntimeException("Agent not found: " + agentId));

    String targetUrl = agent.getBaseUrl() + "/doorsagent/v1/agent/query/execute";
    
    // 5. Prepare Payload (Sending the DECODED SQL to the agent)
    Map<String, Object> requestPayload = new HashMap<>();
    requestPayload.put("sql", decodedSql);
    requestPayload.put("executedBy", currentUsername); 
    requestPayload.put("params", params != null ? params : new HashMap<>());

    // 6. Execute with 10-minute timeout for heavy NIC reports
    log.info("DOORS-MASTER: Dispatching Dry-Run to {}...", agent.getDisplayName());
    
    List<Map<String, Object>> resultData = webClientBuilder.build()
        .post()
        .uri(targetUrl)
        .bodyValue(requestPayload)
        .retrieve()
        .bodyToFlux(new ParameterizedTypeReference<Map<String, Object>>() {})
        .timeout(Duration.ofMinutes(30)) 
        .collectList()
        .block(); 

    Map<String, Object> responseMap = new HashMap<>();
    responseMap.put("success", true);
    responseMap.put("data", resultData); 
    responseMap.put("node", agentId);
    
    return responseMap;
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