package org.gepnic.doors.masterapi.dto.ocds;

import lombok.Builder;
import lombok.Data;
import java.util.List;
@Data
@Builder
public class OcdsItem {
    private String id;
    private String description;
    private Double quantity;
    private String unit;
}

