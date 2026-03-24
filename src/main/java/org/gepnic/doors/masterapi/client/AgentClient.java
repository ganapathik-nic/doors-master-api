package org.gepnic.doors.masterapi.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import java.util.Map;

@Slf4j
@Component
public class AgentClient {

    // THIS WAS MISSING: The declaration of the variable
    private final WebClient webClient;

    public AgentClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    public Flux<Object> streamData(String baseUrl, String sql, String userId, Map<String, Object> params) {
        return webClient.post()
                .uri(baseUrl + "/api/agent/execute")
                .bodyValue(Map.of(
                    "sql", sql,
                    "userId", userId,
                    "params", params
                ))
                .retrieve()
                .bodyToFlux(Object.class);
    }
    
}