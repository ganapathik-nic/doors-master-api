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
import org.gepnic.doors.masterapi.exception.DoorsApiException;
import org.gepnic.doors.masterapi.service.ConfigurationAuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.*;

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class ConfigurationAuditAspect {
    private static final Set<String> MUTATIONS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final ConfigurationAuditService audit;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    @Around("execution(* org.gepnic.doors.masterapi.controller.*.*(..)) || " +
            "execution(* org.gepnic.doors.masterapi.inbound.*Controller.*(..))")
    public Object auditMutation(ProceedingJoinPoint call) throws Throwable {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null || !MUTATIONS.contains(attrs.getRequest().getMethod())) return call.proceed();
        HttpServletRequest request = attrs.getRequest();
        String uri = request.getRequestURI();
        if (uri.contains("/auth/login") || uri.contains("/captcha") || uri.contains("/audit")) {
            return call.proceed();
        }

        Map<String,Object> requestBody = body(call.getArgs());
        EntityRef entity = entity(uri, requestBody);
        Object before = snapshot(entity);
        String trace = trace(request);
        int status = 200;
        String outcome = "SUCCESS", code = null, message = null;
        Object result = null;
        try {
            result = call.proceed();
            if (result instanceof ResponseEntity<?> response) status = response.getStatusCode().value();
            if (status >= 400) outcome = "REJECTED";
            return result;
        } catch (Throwable ex) {
            outcome = "FAILED";
            status = ex instanceof DoorsApiException dae ? dae.getStatus().value()
                    : ex instanceof SecurityException ? 403
                    : ex instanceof IllegalArgumentException ? 400 : 500;
            code = ex instanceof DoorsApiException dae ? dae.getCode() : ex.getClass().getSimpleName();
            message = ex.getMessage();
            throw ex;
        } finally {
            Object after = outcome.equals("SUCCESS") ? snapshot(entity) : requestBody;
            if (after == null) after = requestBody;
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String actor = auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)
                    ? auth.getName() : "SYSTEM_OR_EXTERNAL";
            String roles = auth == null ? null : auth.getAuthorities().toString();
            String justification = justification(requestBody);
            audit.record(new ConfigurationAuditService.Event(actor, roles,
                    action(call, request), entity.type(), entity.id(), entityName(entity, before, after), before, after,
                    justification, clientIp(request), request.getSession(false) == null ? null : request.getSession(false).getId(),
                    trace, uri, request.getMethod(), status, outcome, code, message));
        }
    }

    private Object snapshot(EntityRef entity) {
        if (entity == null || entity.sql() == null || entity.id() == null) return null;
        if ("API_CLIENT".equals(entity.type())) return apiClientSnapshot(entity.id());
        try {
            return db.query(entity.sql(), rs -> {
                if (!rs.next()) return null;
                String value = rs.getString(1);
                if (value == null) return null;
                try {
                    return json.readValue(value, new TypeReference<Map<String,Object>>() {});
                } catch (Exception malformedSnapshot) {
                    log.warn("Unable to parse configuration audit snapshot type={} id={}",
                            entity.type(), entity.id());
                    return Map.of("snapshot", "UNAVAILABLE");
                }
            }, entity.id());
        } catch (Exception ex) {
            log.warn("Unable to take configuration audit snapshot type={} id={}", entity.type(), entity.id());
            return null;
        }
    }

    private Object apiClientSnapshot(String id) {
        try {
            long clientId = Long.parseLong(id);
            Map<String,Object> value = new LinkedHashMap<>(db.queryForMap("""
                    SELECT client_id AS "clientId", client_name AS "clientName",
                           allowed_ips AS "allowedIps", is_active AS "isActive",
                           is_encryption_enabled AS "encryptionEnabled",
                           client_public_key AS "clientPublicKey"
                    FROM external_api_clients WHERE client_id=?
                    """, clientId));
            try {
                value.put("authorizedAgents", db.queryForList(
                        "SELECT agent_id FROM user_authorized_agents WHERE user_name=? ORDER BY agent_id",
                        String.class, value.get("clientName")));
            } catch (Exception optionalAgentsUnavailable) {
                value.put("authorizedAgents", List.of());
            }
            try {
                value.put("queryMappings", db.queryForList("""
                        SELECT query_id AS "queryId", response_filter_column AS "filterColumn",
                               response_filter_value AS "filterValue"
                        FROM external_client_query_map WHERE client_id=? ORDER BY query_id
                        """, clientId));
            } catch (Exception optionalMappingsUnavailable) {
                value.put("queryMappings", List.of());
            }
            return value;
        } catch (Exception ex) {
            log.warn("Unable to take API-client configuration snapshot id={}: {}", id, ex.getMessage());
            return null;
        }
    }

    private EntityRef entity(String uri, Map<String,Object> body) {
        String[] parts = uri.split("/");
        String last = parts.length == 0 ? null : parts[parts.length - 1];
        if (uri.matches(".*/admin/users/\\d+/(vpn|role|approve|reject|mfa/reset|password/reset)$")) {
            String id = segmentBefore(uri, uri.contains("/mfa/") || uri.contains("/password/") ? 2 : 1);
            return new EntityRef("USER", id, null,
                    "SELECT row_to_json(x)::text FROM (SELECT user_id,username,email,role,is_active,status,vpn_ip,vpn_certificate_reference,vpn_status,mfa_enabled,password_reset_required FROM users WHERE user_id=?) x");
        }
        if (uri.matches(".*/templates/mapping/\\d+$") || uri.matches(".*/templates/status/\\d+$")) {
            String id = last;
            return new EntityRef("QUERY", id, null,
                    "SELECT row_to_json(x)::text FROM (SELECT s.query_id,s.unique_name,s.status,s.sql_text,s.governance_note,s.proposer_id,s.approver_id,COALESCE(array_agg(a.agent_id) FILTER (WHERE a.agent_id IS NOT NULL),'{}') authorized_agents FROM sql_templates s LEFT JOIN sql_template_authorized_agents a ON a.query_id=s.query_id WHERE s.query_id=? GROUP BY s.query_id) x");
        }
        if (uri.matches(".*/infrastructure/(update|deactivate|restore)/[^/]+$")) {
            return new EntityRef("AGENT", last, last,
                    "SELECT row_to_json(x)::text FROM (SELECT agent_id,display_name,base_url,agent_instance_code,agent_type,target_db_host,target_db_port,target_db_name,target_db_user,is_active,is_sandbox,status,updated_by,updated_at FROM agents WHERE agent_id=?) x");
        }
        if (uri.contains("/api-clients/") && uri.matches(".*/api-clients/\\d+/.*")) {
            String id = segmentAfter(parts, "api-clients");
            return new EntityRef("API_CLIENT", id, null,
                    "SELECT row_to_json(x)::text FROM (SELECT c.client_id,c.client_name,c.allowed_ips,c.is_active,c.is_encryption_enabled,c.client_public_key," +
                    "COALESCE((SELECT jsonb_agg(agent_id ORDER BY agent_id) FROM user_authorized_agents WHERE user_name=c.client_name),'[]'::jsonb) authorized_agents," +
                    "COALESCE((SELECT jsonb_agg(jsonb_build_object('queryId',query_id,'filterColumn',response_filter_column,'filterValue',response_filter_value) ORDER BY query_id) FROM external_client_query_map WHERE client_id=c.client_id),'[]'::jsonb) query_mappings " +
                    "FROM external_api_clients c WHERE c.client_id=?) x");
        }
        if (uri.contains("/governance/writes") || uri.contains("/admin/inbound-services")) {
            String id = segmentAfter(parts, "writes");
            return new EntityRef("WRITE_SERVICE", id, Objects.toString(body.get("serviceCode"), null), null);
        }
        if (uri.contains("/documents")) return new EntityRef("DOCUMENT_SERVICE", lastNumeric(parts), null, null);
        if (uri.contains("/aira/knowledge/reindex")) return new EntityRef("AIRA_KNOWLEDGE", "KNOWLEDGE_INDEX", null, null);
        return new EntityRef("CONFIGURATION", lastNumeric(parts), null, null);
    }

    private Map<String,Object> body(Object[] args) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (Object arg : args) {
            if (arg == null || arg instanceof HttpServletRequest || arg instanceof HttpServletResponse
                    || arg instanceof Authentication || arg instanceof java.security.Principal) continue;
            try {
                Map<String,Object> value = json.convertValue(arg, new TypeReference<Map<String,Object>>() {});
                result.putAll(value);
            } catch (Exception ignored) { }
        }
        return result;
    }

    private String action(ProceedingJoinPoint call, HttpServletRequest request) {
        return (call.getTarget().getClass().getSimpleName() + "." + call.getSignature().getName())
                .replace("Controller", "").toUpperCase(Locale.ROOT) + "_" + request.getMethod();
    }

    private String trace(HttpServletRequest request) {
        Object existing = request.getAttribute("X-DOORS-TRACE");
        String value = existing == null ? "DOORS-TRC-" + UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.ROOT) : existing.toString();
        request.setAttribute("X-DOORS-TRACE", value);
        return value;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
    }

    private String justification(Map<String,Object> body) {
        for (String key : List.of("justification", "governanceNote", "note", "reason", "reviewNote")) {
            Object value = body.get(key);
            if (value != null && !value.toString().isBlank()) return value.toString();
        }
        return null;
    }

    private String entityName(EntityRef entity, Object before, Object after) {
        if (entity.name() != null && !entity.name().isBlank()) return entity.name();
        for (Object candidate : List.of(after == null ? Map.of() : after, before == null ? Map.of() : before)) {
            if (!(candidate instanceof Map<?,?> map)) continue;
            for (String key : List.of("clientName", "uniqueName", "displayName", "username", "serviceCode", "title")) {
                Object value = map.get(key);
                if (value != null && !value.toString().isBlank()) return value.toString();
            }
        }
        return null;
    }

    private String lastNumeric(String[] parts) {
        for (int i=parts.length-1;i>=0;i--) if (parts[i].matches("\\d+")) return parts[i];
        return null;
    }

    private String segmentAfter(String[] parts, String marker) {
        for (int i=0;i<parts.length-1;i++) {
            if (marker.equals(parts[i]) && parts[i+1].matches("\\d+")) return parts[i+1];
        }
        return null;
    }

    private String segmentBefore(String uri, int count) {
        String[] parts = uri.split("/");
        return parts[Math.max(0, parts.length - 1 - count)];
    }

    private record EntityRef(String type, String id, String name, String sql) { }
}
