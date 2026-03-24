package org.gepnic.doors.masterapi.aspect;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

@Component
@Slf4j
@RequiredArgsConstructor
public class AuthenticationEvents {

    private final JdbcTemplate jdbcTemplate;
    @EventListener
    @Transactional
    public void onFailure(AbstractAuthenticationFailureEvent failure) {
        String username = failure.getAuthentication().getName();
        String error = failure.getException().getMessage();
        
        // 🚀 Dynamic Endpoint Check for Failure
        String endpoint = "/api/v1/auth/login"; 
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                endpoint = attrs.getRequest().getRequestURI();
            }
        } catch (Exception e) {}

        log.warn("AUDIT: Login Failure for user [{}] - Reason: {}", username, error);
        
        // 🚀 Now passing the 5th parameter (endpoint)
        saveAuthLog(username, "LOGIN_FAILURE", error, 401, endpoint);
    }
    @EventListener
    @Transactional
    public void onSuccess(AuthenticationSuccessEvent success) {
        String username = success.getAuthentication().getName();
        
        // 1. Default values
        String action = "LOGIN_SUCCESS";
        String endpoint = "/api/v1/auth/login";

        // 2. 🚀 The "URI Sniffer" with Fallback
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null && attrs.getRequest() != null) {
                String uri = attrs.getRequest().getRequestURI();
                if (uri != null) {
                    endpoint = uri;
                    if (uri.contains("/logout")) {
                        action = "LOGOUT";
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Audit: Could not determine URI, defaulting to LOGIN_SUCCESS info");
        }

        // 3. 🚀 Ensure saveAuthLog gets the endpoint
        saveAuthLog(username, action, "Authentication event", 200, endpoint);
    }

    private void saveAuthLog(String username, String action, String detail, int status, String endpoint) {
        String clientIp = "UNKNOWN";
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null && attrs.getRequest() != null) {
                clientIp = attrs.getRequest().getRemoteAddr();
            }
        } catch (Exception e) {}

        try {
            jdbcTemplate.update(
                "INSERT INTO unified_audit_logs (" +
                "event_type, username, query_name, endpoint, method, " +
                "status_code, duration_ms, client_ip, error_message, record_count, full_command" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "AUTH_EVENT",
                username,
                action + " [SYSTEM]",
                endpoint,
                "POST",
                status,
                0L,
                clientIp,
                detail,
                0,
                "Session event at " + java.time.LocalDateTime.now()
            );
            log.info("AUDIT-DB-SUCCESS: {} logged for user {}", action, username);
        } catch (Exception e) {
            log.error("AUDIT-DB-ERROR: Failed to insert auth log: {}", e.getMessage());
        }
    }
    
}