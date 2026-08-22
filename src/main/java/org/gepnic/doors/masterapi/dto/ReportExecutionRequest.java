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

    private Long queryId;           // Used by internal UI

    private String queryUniqueName; // Used by External Gateway/API

    private String agentId;         // "ALL" or "Node-01,Node-02"

    private String performedBy;     // User or Client System Name

    private Map<String, Object> params; // SQL parameters map

    private Integer page;

    private Integer pageSize;
}
