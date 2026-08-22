package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.model.Agent;
import org.gepnic.doors.masterapi.service.AgentService;
 
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
 
@Slf4j
@RestController
@RequestMapping("/api/v1/master/agents")
@RequiredArgsConstructor
public class AgentController {
 
    // Must be final for Lombok's @RequiredArgsConstructor to inject it
    private final AgentService agentService;

    /**
     * Fetches active agents for the Dry-Run dropdown.
     * Proposers get sandbox-only nodes; Admins get all active nodes.
     * URL: /api/v1/master/agents/list/active?isSandbox=true
     */
    // InfrastructureController.java
 
    @GetMapping("/list/active")
    public ApiResponse<List<Map<String, Object>>> getActiveAgents(
            @RequestParam(required = false) Boolean isSandbox,
            Authentication authentication) {

        isSandbox = effectiveSandboxFilter(isSandbox, authentication);
        
        log.info("DOORS-MASTER: Fetching active agents. Sandbox filter: {}", isSandbox);
        
        List<Agent> agents = agentService.findActive(isSandbox);
        List<Map<String, Object>> safeAgents = agents.stream()
                .map(agent -> Map.<String, Object>of(
                        "agentId", agent.getAgentId(),
                        "displayName", agent.getDisplayName(),
                        "agentType", agent.getAgentType() != null
                                ? agent.getAgentType()
                                : "INDIVIDUAL",
                        "isActive", Boolean.TRUE.equals(agent.getIsActive()),
                        "isSandbox", Boolean.TRUE.equals(agent.getIsSandbox())
                ))
                .toList();

        return ApiResponse.success(safeAgents, "Active agents retrieved successfully");
    }

    static Boolean effectiveSandboxFilter(Boolean requestedFilter, Authentication authentication) {
        if (authentication != null && authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority().toUpperCase(java.util.Locale.ROOT))
                .anyMatch(authority -> authority.equals("DEVELOPER")
                        || authority.equals("ROLE_DEVELOPER"))) {
            return true;
        }
        return requestedFilter;
    }
}
