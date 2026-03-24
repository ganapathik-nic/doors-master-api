package org.gepnic.doors.masterapi.dto.ocds;

import lombok.Builder;
import lombok.Data;
import java.util.List;
@Data
@Builder
public class OcdsRelease {
    private String ocid;
    private String id;
    private String date;
    private List<String> tag; // 🚀 Change String to List<String>
    private String initiationType;
    private OcdsTender tender;
    private List<OcdsAward> awards;
}
