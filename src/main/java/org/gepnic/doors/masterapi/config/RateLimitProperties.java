package org.gepnic.doors.masterapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Data;

@Data
@Configuration
@ConfigurationProperties(prefix = "doors.security") // Notice I moved it up one level
public class RateLimitProperties {
    private String gatewayPathPattern; // Matches 'doors.security.gateway-path-pattern'
    private RateLimitConfig rateLimit = new RateLimitConfig();

    @Data
    public static class RateLimitConfig {
        private Policy ui = new Policy();
        private Policy gateway = new Policy();
    }

    @Data
    public static class Policy {
        private int capacity;
        private int refillMinutes;
        private int penaltyMinutes;
    }
}
