package org.gepnic.doors.masterapi.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.service.AiraService;
import org.gepnic.doors.masterapi.service.AiraChatHistoryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/v1/master/aira")
public class AIraController {
    private final AiraService airaService;
    private final AiraChatHistoryService historyService;

    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<Map<String, Object>>> chat(@RequestBody Map<String, String> payload,
                                                                 Authentication authentication,
                                                                 HttpServletRequest request,
                                                                 HttpServletResponse response) {
        String traceId = newTraceId();
        response.setHeader("X-Trace-ID", traceId);
        try {
            Map<String, Object> result = airaService.chat(authentication.getName(), authorities(authentication),
                    payload.get("prompt"), traceId, clientIp(request), false);
            result.putAll(historyService.record(authentication.getName(), conversationId(payload.get("conversationId")),
                    payload.get("prompt"), result));
            return ResponseEntity.ok(ApiResponse.success(result, "Aira response generated"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ApiResponse.error(ex.getMessage(), 400));
        } catch (SecurityException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(ex.getMessage(), 403));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.error(ex.getMessage(), 503));
        } catch (RuntimeException ex) {
            log.error("Aira request failed [traceId={}, user={}]: {}", traceId, authentication.getName(), ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("Aira could not complete the response within the local model time limit. Please retry.", 503));
        }
    }

    @PostMapping("/chat/refine")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refine(@RequestBody Map<String, String> payload,
                                                                   Authentication authentication,
                                                                   HttpServletRequest request,
                                                                   HttpServletResponse response) {
        String traceId = newTraceId();
        response.setHeader("X-Trace-ID", traceId);
        try {
            Map<String, Object> result = airaService.chat(authentication.getName(), authorities(authentication),
                    payload.get("prompt"), traceId, clientIp(request), true);
            Long exchangeId = conversationId(payload.get("exchangeId"));
            if (exchangeId != null) historyService.updateAnswer(authentication.getName(), exchangeId, result);
            return ResponseEntity.ok(ApiResponse.success(result, "Aira response refined"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ApiResponse.error(ex.getMessage(), 400));
        } catch (SecurityException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(ex.getMessage(), 403));
        } catch (RuntimeException ex) {
            log.error("Aira refinement failed [traceId={}, user={}]: {}", traceId, authentication.getName(), ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("AI refinement did not complete. The grounded answer is still available.", 503));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(airaService.status());
    }

    @GetMapping("/conversations")
    public ResponseEntity<ApiResponse<java.util.List<Map<String, Object>>>> conversations(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(historyService.conversations(authentication.getName()),
                "Aira conversations loaded"));
    }

    @GetMapping("/conversations/{conversationId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> conversation(@PathVariable Long conversationId,
                                                                         Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(historyService.conversation(authentication.getName(), conversationId),
                "Aira conversation loaded"));
    }

    @PutMapping("/responses/{exchangeId}/rating")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rate(@PathVariable Long exchangeId,
                                                                 @RequestBody Map<String, Object> payload,
                                                                 Authentication authentication) {
        int rating = payload.get("rating") instanceof Number number ? number.intValue() : 0;
        String comment = payload.get("comment") == null ? null : String.valueOf(payload.get("comment"));
        return ResponseEntity.ok(ApiResponse.success(historyService.rate(authentication.getName(), exchangeId, rating, comment),
                "Aira response rating saved"));
    }

    @GetMapping("/analytics")
    public ResponseEntity<ApiResponse<Map<String, Object>>> analytics() {
        return ResponseEntity.ok(ApiResponse.success(historyService.analytics(), "Aira usage analytics loaded"));
    }

    @PostMapping("/knowledge/reindex")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reindexKnowledge() {
        try {
            int count = airaService.reindexKnowledge();
            return ResponseEntity.ok(ApiResponse.success(
                    Map.of("indexedSegments", count, "status", "SUCCESS"),
                    "Knowledge base reindexed successfully (" + count + " segments indexed)"
            ));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.error(ex.getMessage(), 503));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to reindex knowledge base: " + ex.getMessage(), 500));
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
    }

    private static String newTraceId() {
        return "DOORS-TRC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
    }

    private static Set<String> authorities(Authentication authentication) {
        return authentication.getAuthorities().stream().map(a -> a.getAuthority()).collect(Collectors.toSet());
    }

    private static Long conversationId(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Long.valueOf(value); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid conversation identifier"); }
    }
}
