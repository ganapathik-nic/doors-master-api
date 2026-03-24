package org.gepnic.doors.masterapi.dto.ocds;

import lombok.Builder;
import lombok.Data;
import java.util.List;
@Data
@Builder
public class OcdsTender {
    private String id;
    private String title;
    private String status;
    private String mainProcurementCategory; // Goods, Works, or Services
    private List<OcdsItem> items; // Mapped from your BoQDetails
}
