package org.gepnic.doors.masterapi.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * Data Transfer Object for report execution requests.
 * Maps incoming JSON from the Interactive Report Viewer to the service layer.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ReportExecutionRequest implements Serializable {

    @jakarta.validation.constraints.Positive
    private Long queryId;           // Used by internal UI

    @jakarta.validation.constraints.Size(max=100)
    private String queryUniqueName; // Used by External Gateway/API

    @jakarta.validation.constraints.Size(max=4096)
    private String agentId;         // "ALL" or "Node-01,Node-02"

    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    private String performedBy;     // Set by the server, never deserialized from the request.

    @jakarta.validation.constraints.Size(max=100)
    private Map<String, Object> params; // SQL parameters map

    @jakarta.validation.constraints.Min(1)
    private Integer page;

    @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(200)
    private Integer pageSize;

    @jakarta.validation.constraints.AssertTrue(message="A query ID or name is required")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isQuerySelected() { return queryId != null || (queryUniqueName != null && !queryUniqueName.isBlank()); }
}
