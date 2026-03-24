package org.gepnic.doors.masterapi.dto;

import lombok.Data;

@Data
public class AgentRegistryRequest {
    private String agentId;
    private String displayName;
    private String baseUrl;
    private String agentInstanceCode;
    private Boolean isSandbox; // Matches your Agent.java field
    private String updatedBy;
}