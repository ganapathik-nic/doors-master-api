package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.client.AgentClient;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/master")
@RequiredArgsConstructor
public class MasterController {

    private final AgentClient agentClient;

    /**
     * STREAMS DATA FROM AGENT NODES
     * Dynamically resolves the User ID from the Security Context to avoid hardcoding "ADMIN".
     */
    @PostMapping("/stream")
    public Flux<Object> handleStream(
            @RequestParam String url, 
            @RequestParam String sql) {

        // 1. Pull the authenticated username from the Reactive Context
        return ReactiveSecurityContextHolder.getContext()
            .map(securityContext -> securityContext.getAuthentication().getName())
            .defaultIfEmpty("SYSTEM_AUTO") // Fallback if ran via internal task
            .flatMapMany(resolvedUser -> {
                
                log.info("DOORS-MASTER: User '{}' requested stream from node {}", resolvedUser, url);

                // 2. FIX: Added Collections.emptyMap() to satisfy the 4-argument signature 
                // in AgentClient.streamData(String, String, String, Map)
                return agentClient.streamData(url, sql, resolvedUser, Collections.emptyMap());
            });
    }
}