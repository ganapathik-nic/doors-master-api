package org.gepnic.doors.masterapi.dto.ocds;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class OcdsReleasePackage {
    private String uri;
    private String version;
    private List<OcdsRelease> releases;
}

