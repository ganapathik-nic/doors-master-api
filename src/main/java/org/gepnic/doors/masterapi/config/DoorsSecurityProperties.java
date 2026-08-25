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
@ConfigurationProperties(prefix = "doors.security")
public class DoorsSecurityProperties {
    private List<String> allowedOrigins = new ArrayList<>();
    private boolean sessionCookieSecure = true;
}
