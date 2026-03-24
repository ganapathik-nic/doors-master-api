package org.gepnic.doors.masterapi.aspect;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.gepnic.doors.masterapi.config.AuditContextHolder;
import org.gepnic.doors.masterapi.dto.ReportExecutionRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class AuditAspect {

    private static final String ATTR_TOTAL_RECORD_COUNT = "TOTAL_RECORD_COUNT";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Around("execution(* org.gepnic.doors.masterapi.controller.*.*(..))")
    public Object logEverything(ProceedingJoinPoint joinPoint) throws Throwable {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        HttpServletRequest request = attributes.getRequest();

        String uri = request.getRequestURI();

        if (shouldSkipAudit(uri)) {
            return joinPoint.proceed();
        }

        long start = System.currentTimeMillis();
        Object[] args = joinPoint.getArgs();

        // 🚀 STEP 1: Extract Request Body to find Query details
        Map<String, Object> bodyMap = extractBodyMap(args);
        
        // 🚀 STEP 2: Resolve Query Name (The fix for UNKNOWN_ACTION)
        String detectedQName = resolveActionName(bodyMap, uri);
        
        String detectedAgent = "NA";
        if (bodyMap.containsKey("agentIds") && bodyMap.get("agentIds") != null) {
            detectedAgent = String.valueOf(bodyMap.get("agentIds"));
        } else if (bodyMap.containsKey("agentId") && bodyMap.get("agentId") != null) {
            detectedAgent = String.valueOf(bodyMap.get("agentId"));
        }

        Object result = null;
        int statusCode = 200;
        String errorMessage = null;

        try {
            result = joinPoint.proceed();
            return result;
        } catch (Exception ex) {
            statusCode = 500;
            errorMessage = ex.getMessage();
            throw ex;
        } finally {
            try {
                long duration = System.currentTimeMillis() - start;
                int records = resolveRecordCount(request);

                String clientIp = resolveClientIp(request);
                String username = resolveUsername();
                String fullCurl = generateCurl(request, bodyMap);
                
                // 🚀 STEP 3: Concatenate Action + Agent for the final display
                String finalActionColumn = detectedQName + " " + detectedAgent + "";

                log.info("DOORS-AUDIT: action={}, records={}, status={}, time={}ms", 
                         finalActionColumn, records, statusCode, duration);

                if (shouldPersistAudit(uri)) {
                    jdbcTemplate.update(
                            "INSERT INTO unified_audit_logs (" +
                                    "event_type, username, query_name, endpoint, method, " +
                                    "status_code, duration_ms, client_ip, error_message, " +
                                    "record_count, full_command, execution_time" +
                                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                            "DATA_RETRIEVAL",
                            username,
                            finalActionColumn,
                            uri,
                            request.getMethod(),
                            statusCode,
                            duration,
                            clientIp,
                            errorMessage,
                            records,
                            fullCurl
                    );
                }
            } catch (Exception auditEx) {
                log.error("AUDIT_LOG_FAIL: {}", auditEx.getMessage());
            } finally {
                AuditContextHolder.clear();
            }
        }
    }

    private String resolveActionName(Map<String, Object> bodyMap, String uri) {
        // 1. Check if the body contains queryUniqueName (Sent by updated Vue UI)
        if (bodyMap.containsKey("queryUniqueName") && bodyMap.get("queryUniqueName") != null) {
            return String.valueOf(bodyMap.get("queryUniqueName"));
        }

        // 2. Check if the body contains queryId (Fallback to DB lookup)
        if (bodyMap.containsKey("queryId") && bodyMap.get("queryId") != null) {
            try {
                Object qId = bodyMap.get("queryId");
                return jdbcTemplate.queryForObject(
                        "SELECT unique_name FROM sql_templates WHERE query_id = ?",
                        String.class,
                        qId
                );
            } catch (Exception e) {
                return "QUERY_ID_" + bodyMap.get("queryId");
            }
        }

        // 3. Fallback to URI parsing (For GET requests or legacy orchestration)
        return detectQueryNameFromUri(uri);
    }

    private String detectQueryNameFromUri(String uri) {
        if (uri == null || uri.isBlank()) return "UNKNOWN_ACTION";
        
        if (uri.contains("/orchestrate/")) {
            try {
                return URLDecoder.decode(
                        uri.substring(uri.lastIndexOf("/") + 1),
                        StandardCharsets.UTF_8
                );
            } catch (Exception e) {
                return "ORCHESTRATE_ERROR";
            }
        }
        return "UNKNOWN_ACTION";
    }

    private Map<String, Object> extractBodyMap(Object[] args) {
        Map<String, Object> bodyMap = new HashMap<>();
        if (args == null) return bodyMap;

        for (Object arg : args) {
            if (arg == null || arg instanceof HttpServletRequest || arg instanceof HttpServletResponse) continue;

            try {
                Map<String, Object> map = objectMapper.convertValue(arg, new TypeReference<Map<String, Object>>() {});
                if (map != null) bodyMap.putAll(map);
            } catch (Exception ignored) {}
        }
        return bodyMap;
    }

    private int resolveRecordCount(HttpServletRequest request) {
        Object attrCount = request.getAttribute(ATTR_TOTAL_RECORD_COUNT);
        Integer contextCount = AuditContextHolder.getRecordCount();

        if (attrCount instanceof Number) return ((Number) attrCount).intValue();
        if (attrCount instanceof String) {
            try { return Integer.parseInt((String) attrCount); } catch (Exception ignored) {}
        }
        return (contextCount != null) ? contextCount : 0;
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String resolveUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            return auth.getName();
        }
        return "EXTERNAL_CLIENT";
    }
    private String generateCurl(HttpServletRequest request, Map<String, Object> body) {
        try {
            StringBuilder curl = new StringBuilder("curl -X ")
                    .append(request.getMethod())
                    .append(" \"")
                    .append(request.getRequestURL())
                    .append("\" \\\n");

            // 🚀 1. Capture Essential Headers
            String apiKey = request.getHeader("X-API-KEY");
            if (apiKey != null) {
                curl.append("  -H \"X-API-KEY: ").append(apiKey).append("\" \\\n");
            }
            curl.append("  -H \"Content-Type: application/json\" \\\n");

            // 🚀 2. Capture and Format Body
            if (body != null && !body.isEmpty()) {
                Map<String, Object> cleanBody = new HashMap<>(body);
                
                // Remove internal audit-helper fields so the curl remains pure
                cleanBody.remove("queryUniqueName");
                cleanBody.remove("performedBy"); // Optional: remove if you want it exactly like the GUIDE

                String jsonBody = objectMapper.writerWithDefaultPrettyPrinter()
                                              .writeValueAsString(cleanBody);
                
                // Indent the JSON for better readability in logs
                curl.append("  -d '").append(jsonBody).append("'");
            }

            return curl.toString();
        } catch (Exception e) {
            log.warn("DOORS-AUDIT: Failed to generate exact curl", e);
            return "curl-generation-error";
        }
    }
/* 
    private String generateCurl(HttpServletRequest request, Map<String, Object> body) {
        try {
            StringBuilder curl = new StringBuilder("curl -X ").append(request.getMethod())
                    .append(" '").append(request.getRequestURL()).append("'");

            if (body != null && !body.isEmpty()) {
                Map<String, Object> cleanBody = new HashMap<>(body);
                cleanBody.remove("queryUniqueName"); // Remove audit-only field from curl
                curl.append(" -d '").append(objectMapper.writeValueAsString(cleanBody)).append("'");
            }
            return curl.toString();
        } catch (Exception e) {
            return "curl-error";
        }
    }
*/
    private boolean shouldSkipAudit(String uri) {
        return uri.contains("/audit-logs") || uri.contains("/status") || uri.contains("/auth/");
    }

    private boolean shouldPersistAudit(String uri) {
        return uri.contains("/orchestrate") || uri.contains("/execute");
    }
}