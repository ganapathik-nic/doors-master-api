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

    public Map<String, Object> executeDryRun(String agentId, String sqlText, Map<String, Object> params) {
        String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();

        // 🛡️ Authorization Check
        String checkMapping = "SELECT COUNT(*) FROM user_authorized_agents WHERE user_name ILIKE ? AND agent_id = ?";
        Integer count = jdbcTemplate.queryForObject(checkMapping, Integer.class, currentUsername, agentId);
        
        if (count == null || count == 0) {
            log.error("🚨 SECURITY ALERT: Unauthorized dry-run attempt by {} on agent {}", currentUsername, agentId);
            throw new SecurityException("Access Denied: You are not authorized to execute on agent " + agentId);
        }

        validateParams(params);

        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new RuntimeException("Agent not found: " + agentId));

        String targetUrl = agent.getBaseUrl() + "/doorsagent/v1/agent/query/execute";
        
        Map<String, Object> requestPayload = new HashMap<>();
        requestPayload.put("sql", sqlText);
        requestPayload.put("executedBy", currentUsername); 
        requestPayload.put("params", params != null ? params : new HashMap<>());

        try {
            log.info("DOORS-MASTER: Secure dispatch to {}...", agent.getDisplayName());
            
            List<Map<String, Object>> resultData = webClientBuilder.build()
                .post()
                .uri(targetUrl)
                .bodyValue(requestPayload)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofMinutes(10)) 
                .collectList()
                .block();

            Map<String, Object> responseMap = new HashMap<>();
            responseMap.put("success", true);
            responseMap.put("data", resultData); 
            responseMap.put("node", agentId);
            return responseMap;

        } catch (Exception e) {
            log.error("DOORS-MASTER: Agent Communication Failure: {}", e.getMessage());
            Map<String, Object> errorMap = new HashMap<>();
            errorMap.put("success", false);
            errorMap.put("message", "Agent Communication Error: " + e.getMessage());
            return errorMap;
        }
    }

    public Map<String, Object> executeOrchestratedReport(SqlTemplate template, Map<String, Object> userProvidedParams) {
        validateParams(userProvidedParams);

        List<String> targetAgentIds = getAuthorizedExecutionTargets(template);
        if (targetAgentIds.isEmpty()) {
            throw new SecurityException("No authorized nodes assigned to your account for this report.");
        }

        Map<String, Object> globalResults = new HashMap<>();
        for (String agentId : targetAgentIds) {
            Map<String, Object> result = executeDryRun(agentId, template.getSqlText(), userProvidedParams);
            globalResults.put(agentId, result);
        }
        return globalResults;
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