package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.model.Agent;
import org.gepnic.doors.masterapi.model.SqlTemplate;
import org.gepnic.doors.masterapi.model.User;
import org.gepnic.doors.masterapi.repository.AgentRepository;
import org.gepnic.doors.masterapi.repository.UserRepository;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutionService {

    private final AgentRepository agentRepository;
    private final UserRepository userRepository;
    
    /**
     * Injected WebClient Builder (configured in WebConfig with 100MB buffer 
     * and SSL bypass for government CAs).
     */
    private final WebClient.Builder webClientBuilder;

    /**
     * CORE SECURITY LOGIC: Intersection Check
     */
    public List<String> getAuthorizedExecutionTargets(SqlTemplate template) {
        String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();
        
        User user = userRepository.findByUsername(currentUsername)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + currentUsername));

        List<String> queryAgents = template.getAuthorizedAgents();
        List<String> userAgents = user.getAssignedAgents();

        return queryAgents.stream()
                .filter(userAgents::contains)
                .collect(Collectors.toList());
    }

    /**
     * Executes the query on a single agent.
     * Compatible with the original SQL using json_agg.
     */
    public Map<String, Object> executeDryRun(String agentId, String sqlText, Map<String, Object> params) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new RuntimeException("Agent not found: " + agentId));

        String targetUrl = agent.getBaseUrl() + "/doorsagent/v1/agent/query/execute";
        
        Map<String, Object> requestPayload = new HashMap<>();
        requestPayload.put("sql", sqlText);
        requestPayload.put("params", params != null ? params : new HashMap<>());

        try {
            log.info("DOORS-MASTER: Fetching buffered result from {}...", agent.getDisplayName());
            
            /**
             * We fetch the result as a List of Maps. 
             * With original json_agg SQL, this list will contain exactly ONE row 
             * where the JSON array is the first column.
             */
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

    /**
     * Orchestrated Execution across all authorized nodes.
     */
    public Map<String, Object> executeOrchestratedReport(SqlTemplate template, Map<String, Object> userProvidedParams) {
        List<String> targetAgentIds = getAuthorizedExecutionTargets(template);
        if (targetAgentIds.isEmpty()) {
            throw new SecurityException("No authorized nodes assigned to your account for this report.");
        }

        Map<String, Object> globalResults = new HashMap<>();
        for (String agentId : targetAgentIds) {
            log.info("DOORS-MASTER: Dispatching orchestration to agent: {}", agentId);
            Map<String, Object> result = executeDryRun(agentId, template.getSqlText(), userProvidedParams);
            globalResults.put(agentId, result);
        }
        return globalResults;
    }
}