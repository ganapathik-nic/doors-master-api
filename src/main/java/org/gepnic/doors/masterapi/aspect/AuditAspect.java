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
import org.gepnic.doors.masterapi.exception.EncryptionException;
import org.gepnic.doors.masterapi.exception.DoorsApiException;
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
import java.util.UUID;

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
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return joinPoint.proceed();
        }

        HttpServletRequest request = attributes.getRequest();
        String uri = request.getRequestURI();

        if (shouldSkipAudit(uri)) {
            return joinPoint.proceed();
        }

        long start = System.currentTimeMillis();
        Object[] args = joinPoint.getArgs();

        Map<String, Object> bodyMap = extractBodyMap(args);
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
        String errorCode = null;
        String isolatedTraceId = null;

        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable ex) {
            if (ex instanceof DoorsApiException doorsException) {
                statusCode = doorsException.getStatus().value();
                errorCode = doorsException.getCode();
            } else if (ex instanceof SecurityException) {
                statusCode = 403;
                errorCode = "DOORS-AUTH-ACCESS-DENIED";
            } else if (ex instanceof EncryptionException) {
                statusCode = 500;
                errorCode = "DOORS-CRYPTO-FAILED";
            } else if (ex instanceof IllegalArgumentException) {
                statusCode = 400;
                errorCode = "DOORS-REQUEST-INVALID";
            } else {
                statusCode = 500;
                errorCode = "DOORS-INTERNAL-ERROR";
            }
            String rawMessage = ex.getMessage() != null ? ex.getMessage() : "Internal Gateway Failure";
            
            Object boundTrace = request.getAttribute("X-DOORS-TRACE");
            if (boundTrace != null) {
                isolatedTraceId = boundTrace.toString();
            } else {
                isolatedTraceId = "DOORS-TRC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            }

            errorMessage = rawMessage;
            throw ex;
        } finally {
            try {
                // 🚀 1. CAPTURE CONTROLLER-BOUND TRACE ID
                Object boundTrace = request.getAttribute("X-DOORS-TRACE");
                if (boundTrace != null) {
                    isolatedTraceId = boundTrace.toString();
                } else if (isolatedTraceId == null) {
                    isolatedTraceId = "DOORS-TRC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
                }

                long duration = System.currentTimeMillis() - start;
                int records = resolveRecordCount(request);

                String clientIp = resolveClientIp(request);
                String username = resolveUsername();
                String fullCurl = generateCurl(request, bodyMap);

                Object resolvedAgents = request.getAttribute("X-DOORS-AGENTS");
                if (resolvedAgents != null && !resolvedAgents.toString().isBlank()) {
                    detectedAgent = resolvedAgents.toString();
                }
                 
                String finalActionColumn = detectedQName + " " + detectedAgent;

                log.info("DOORS-AUDIT: action={}, records={}, status={}, trace={}, time={}ms, uri={}", 
                        finalActionColumn, records, statusCode, isolatedTraceId, duration, uri);

                if (shouldPersistAudit(uri)) {
                    // 🚀 2. UNIFIED SINGLE INSERT WITH FULL COLUMN ALIGNMENT
                    int rowsWritten = jdbcTemplate.update(
                            "INSERT INTO unified_audit_logs (" +
                                    "event_type, username, query_name, endpoint, method, " +
                                    "status_code, duration_ms, client_ip, error_code, error_message, " +
                                    "record_count, full_command, trace_id, execution_time" +
                                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                            "DATA_RETRIEVAL",
                            username,
                            finalActionColumn,
                            uri,
                            request.getMethod(),
                            statusCode,
                            duration,
                            clientIp,
                            errorCode,
                            errorMessage,
                            records,
                            fullCurl,
                            isolatedTraceId
                    );

                    log.info("✅ AUDIT ROW WRITTEN SUCCESSFULLY: log_id trace=[{}] rows={}", isolatedTraceId, rowsWritten);
                } else {
                    log.warn("⚠️ AUDIT SKIPPED BY PERSIST RULE for URI: [{}]", uri);
                }
            } catch (Exception auditEx) {
                log.error("💥 AUDIT_LOG_PERSIST_ERROR for trace [{}]: {}", isolatedTraceId, auditEx.getMessage(), auditEx);
            } finally {
                AuditContextHolder.clear();
            }
        }
    }

    private String resolveActionName(Map<String, Object> bodyMap, String uri) {
        if (bodyMap.containsKey("queryUniqueName") && bodyMap.get("queryUniqueName") != null) {
            return String.valueOf(bodyMap.get("queryUniqueName"));
        }

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

            String apiKey = request.getHeader("X-API-KEY");
            if (apiKey != null) {
                curl.append("  -H \"X-API-KEY: ").append(apiKey).append("\" \\\n");
            }
            curl.append("  -H \"Content-Type: application/json\" \\\n");

            if (body != null && !body.isEmpty()) {
                Map<String, Object> cleanBody = new HashMap<>(body);
                cleanBody.remove("queryUniqueName");
                cleanBody.remove("performedBy");

                String jsonBody = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(cleanBody);
                curl.append("  -d '").append(jsonBody).append("'");
            }

            return curl.toString();
        } catch (Exception e) {
            log.warn("DOORS-AUDIT: Failed to generate exact curl", e);
            return "curl-generation-error";
        }
    }

    private boolean shouldSkipAudit(String uri) {
        return uri.contains("/audit-logs") || uri.contains("/status") || uri.contains("/auth/") || uri.contains("/telemetry/");
    }

    private boolean shouldPersistAudit(String uri) {
        return uri.contains("/orchestrate") || uri.contains("/execute") || uri.contains("/gateway");
    }
}
