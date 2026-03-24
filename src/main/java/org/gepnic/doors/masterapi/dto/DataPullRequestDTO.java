package org.gepnic.doors.masterapi.dto;

import lombok.Data;

@Data
public class DataPullRequestDTO {
    private String requestTitle;
    private String targetAgentId;
    private String justification;
    private String sampleJson;
    private String requestedBy;
    // Note: The MultipartFile is handled separately in the Controller
}