package org.gepnic.doors.masterapi.dto;

import lombok.Data;
import java.util.List;

@Data
public class MappingRequest {
    private List<String> agentIds;
}
