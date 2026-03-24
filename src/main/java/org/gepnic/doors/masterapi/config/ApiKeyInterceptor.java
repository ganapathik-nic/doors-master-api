package org.gepnic.doors.masterapi.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.repository.ApiClientRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyInterceptor implements HandlerInterceptor {

    private final ApiClientRepository apiClientRepository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;

        String incomingIp = getClientIp(request);
        String apiKey = request.getHeader("X-API-KEY");
        
        Optional<ApiClient> clientOpt = (apiKey != null && !apiKey.isEmpty()) 
                ? apiClientRepository.findByApiKey(apiKey) : Optional.empty();

        // 🚀 GATE 1: MANDATORY IP CHECK
        if (clientOpt.isPresent()) {
            ApiClient client = clientOpt.get();
            if (!client.getIpWhitelist().isEmpty() && !client.getIpWhitelist().contains(incomingIp)) {
                logSecurityFailure(request, "IP " + incomingIp + " unauthorized", apiKey, client);
                response.setStatus(403);
                response.getWriter().write("Forbidden: IP not authorized");
                return false; 
            }
        }

        // 🚀 GATE 2: JWT Bypass (Check this before rejecting based on missing Key)
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            return true;
        }

        // 🚀 GATE 3: External enforce
        if (clientOpt.isEmpty()) {
            logSecurityFailure(request, "Invalid API Key", apiKey, null);
            response.setStatus(401);
            return false;
        }

        return true; 
    }

    private void logSecurityFailure(HttpServletRequest request, String reason, String apiKey, ApiClient client) {
        try {
            String uri = request.getRequestURI();
            String queryName = "BLOCKED_ACCESS";
            
            // Extract Query Name from URL safely
            if (uri.contains("/orchestrate/")) {
                queryName = URLDecoder.decode(uri.substring(uri.lastIndexOf("/") + 1), StandardCharsets.UTF_8);
            }

            String clientName = (client != null) ? client.getClientName() : "UNKNOWN_CLIENT";
            
            // 🚀 We avoid request.getReader() here to keep logging alive
            // We use the Client Name as a proxy for the "Agent" in the Query column for 403s
            String displayAction = queryName + " [REJECTED]";

            jdbcTemplate.update(
    "INSERT INTO unified_audit_logs (event_type, username, query_name, endpoint, method, " +
    "status_code, client_ip, error_message, record_count, full_command, duration_ms, execution_time) " +
    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
    "SECURITY_VIOLATION",
    clientName,
    displayAction, 
    uri,
    request.getMethod(),
    403,
    getClientIp(request),
    reason,
    0,                   // record_count
    "curl -X " + request.getMethod() + " '" + request.getRequestURL() + "'", // full_command
    0                    // duration_ms
);
        } catch (Exception e) {
            log.error("FAILSAFE: Audit log failed - {}", e.getMessage());
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) ip = request.getRemoteAddr();
        if ("0:0:0:0:0:0:0:1".equals(ip)) ip = "127.0.0.1";
        return ip.split(",")[0].trim();
    }
}