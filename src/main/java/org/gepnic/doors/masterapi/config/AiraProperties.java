package org.gepnic.doors.masterapi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "doors.aira")
public class AiraProperties {
    private boolean enabled = false;
    private String baseUrl = "http://127.0.0.1:11434";
    private String model = "qwen2.5:1.5b";
    private String embeddingModel = "nomic-embed-text";
    private long timeoutSeconds = 180;
    private int maxPromptChars = 4000;
    private String vectorStorePath = "security/knowledge/doors-vector-store.json";
    private int ragMaxResults = 4;
    private double ragMinScore = 0.55;
}
