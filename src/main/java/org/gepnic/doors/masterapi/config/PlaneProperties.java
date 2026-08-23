package org.gepnic.doors.masterapi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "doors.planes")
public class PlaneProperties {

    private boolean enforced;
    private List<String> adminHosts = new ArrayList<>();
    private List<String> externalHosts = new ArrayList<>();
    private List<String> apiHosts = new ArrayList<>();
}
