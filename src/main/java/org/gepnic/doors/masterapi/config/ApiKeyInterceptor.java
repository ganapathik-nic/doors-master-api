package org.gepnic.doors.masterapi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyInterceptor implements HandlerInterceptor {

    private final ApiClientRepository apiClientRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

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
            if (!Boolean.TRUE.equals(client.getIsActive())) {
                response.sendError(403, "Inactive API client");
                return false;
            }
            if (!client.getIpWhitelist().isEmpty() && !client.getIpWhitelist().contains(incomingIp)) {
                String traceId = logSecurityFailure(
                        request, "IP address is not authorized", client, 403, "DOORS-AUTH-IP-DENIED");
                writeProblem(response, 403, "DOORS-AUTH-IP-DENIED", "ip-address-denied",
                        "IP address denied", "The source IP is not authorized for this API client.", traceId);
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
            String traceId = logSecurityFailure(
                    request, "Invalid API key", null, 401, "DOORS-AUTH-INVALID-API-KEY");
            writeProblem(response, 401, "DOORS-AUTH-INVALID-API-KEY", "invalid-api-key",
                    "Invalid API key", "The supplied API key is missing or invalid.", traceId);
            return false;
        }

        return true; 
    }

    private String logSecurityFailure(
            HttpServletRequest request,
            String reason,
            ApiClient client,
            int status,
            String errorCode) {
        String traceId = "DOORS-TRC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        request.setAttribute("X-DOORS-TRACE", traceId);
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
    "status_code, client_ip, error_code, error_message, record_count, full_command, duration_ms, trace_id, execution_time) " +
    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
    "SECURITY_VIOLATION",
    clientName,
    displayAction, 
    uri,
    request.getMethod(),
    status,
    getClientIp(request),
    errorCode,
    reason,
    0,                   // record_count
    "curl -X " + request.getMethod() + " '" + request.getRequestURL() + "'", // full_command
    0,                   // duration_ms
    traceId
);
        } catch (Exception e) {
            log.error("FAILSAFE: Audit log failed - {}", e.getMessage());
        }
        return traceId;
    }

    private void writeProblem(
            HttpServletResponse response,
            int status,
            String code,
            String problemType,
            String title,
            String detail,
            String traceId) throws Exception {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "https://doors.nic.in/problems/" + problemType);
        problem.put("title", title);
        problem.put("status", status);
        problem.put("detail", detail);
        problem.put("instance", "/problems/occurrences/" + traceId);
        problem.put("code", code);
        problem.put("traceId", traceId);
        problem.put("retryable", false);

        response.setStatus(status);
        response.setHeader("X-DOORS-TRACE", traceId);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), problem);
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = TrustedProxyConfiguration.clientIp(request);
        if ("0:0:0:0:0:0:0:1".equals(ip)) ip = "127.0.0.1";
        return ip.split(",")[0].trim();
    }
}
