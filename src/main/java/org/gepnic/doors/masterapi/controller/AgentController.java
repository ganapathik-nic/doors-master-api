package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.model.Agent;
import org.gepnic.doors.masterapi.service.AgentService;
 
import org.springframework.web.bind.annotation.*;

import java.util.List;
 
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
    public ApiResponse<List<Agent>> getActiveAgents(
            @RequestParam(required = false) Boolean isSandbox) {
        
        log.info("DOORS-MASTER: Fetching active agents. Sandbox filter: {}", isSandbox);
        
        List<Agent> agents = agentService.findActive(isSandbox);
        
        // Fixed: Passing both data and the required success message string
        return ApiResponse.success(agents, "Active agents retrieved successfully");
    }
}