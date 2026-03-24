package org.gepnic.doors.masterapi.dto;

//public package org.gepnic.doors.masterapi.dto;

import lombok.Data;
import java.util.List; // This fix handles the "List cannot be resolved" error

/**
 * DTO for GEMS Multi-Agent Orchestration.
 * Handles the payload from Vue.js for parallel query execution.
 */
@Data
public class MultiAgentRequest {
    private List<String> agentIds;
    private String userId;
}  
