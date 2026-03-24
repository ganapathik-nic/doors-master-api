package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.model.Agent;
import org.gepnic.doors.masterapi.repository.AgentRepository;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * SERVICE: Agent Management
 * Business logic for managing distributed infrastructure nodes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentRepository agentRepository;

    /**
     * Filters agents by active status and optional sandbox flag.
     * * @param isSandbox If true, returns only sandbox nodes. 
     * If false, returns only non-sandbox nodes.
     * If null, returns all active nodes.
     */
    public List<Agent> findActive(Boolean isSandbox) {
        log.debug("DOORS-SERVICE: Fetching active agents. Filter isSandbox: {}", isSandbox);
        
        // Using the @Query method 'findAllActive' to handle the optional boolean logic
        return agentRepository.findAllActive(isSandbox);
    }

    /**
     * Standard fetch for all active agents without filtering.
     * Useful for admin dashboards.
     */
    public List<Agent> findAllActive() {
        return agentRepository.findByIsActiveTrue();
    }
}