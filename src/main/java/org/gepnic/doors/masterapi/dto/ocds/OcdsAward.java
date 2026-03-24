package org.gepnic.doors.masterapi.dto.ocds;

import lombok.Builder;
import lombok.Data;
import java.util.List;
@Data
@Builder
public class OcdsAward {
    private String id;
    private String suppliersName;
    private Double value;
    private String date;
}