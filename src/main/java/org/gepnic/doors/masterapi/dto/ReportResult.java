package org.gepnic.doors.masterapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReportResult(
    @JsonProperty("data") List<Map<String, Object>> data, 
    @JsonProperty("offlineAgents") List<String> offlineAgents,
    @JsonProperty("nodeErrors") Map<String, String> nodeErrors,
    @JsonProperty("pagination") ReportPagination pagination
) {}
