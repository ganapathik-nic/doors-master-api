package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/master/configuration-audit")
@RequiredArgsConstructor
public class ConfigurationAuditController {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    @GetMapping
    public List<Map<String,Object>> search(@RequestParam(required = false) String actor,
                                           @RequestParam(required = false) String entityType,
                                           @RequestParam(required = false) String entityId,
                                           @RequestParam(required = false) String traceId,
                                           @RequestParam(defaultValue = "200") int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT audit_id AS "auditId",occurred_at AS "occurredAt",actor_username AS actor,
                       actor_role AS "actorRole",action,entity_type AS "entityType",entity_id AS "entityId",
                       entity_name AS "entityName",old_value::text AS "oldValue",new_value::text AS "newValue",
                       changed_fields::text AS "changedFields",justification,client_ip AS "clientIp",
                       trace_id AS "traceId",endpoint,http_method AS method,http_status AS status,
                       outcome,failure_code AS "failureCode",failure_message AS "failureMessage"
                FROM configuration_audit_events WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (actor != null && !actor.isBlank()) { sql.append(" AND actor_username ILIKE ?"); args.add("%"+actor.trim()+"%"); }
        if (entityType != null && !entityType.isBlank()) { sql.append(" AND entity_type=?"); args.add(entityType.trim().toUpperCase()); }
        if (entityId != null && !entityId.isBlank()) { sql.append(" AND entity_id=?"); args.add(entityId.trim()); }
        if (traceId != null && !traceId.isBlank()) { sql.append(" AND trace_id=?"); args.add(traceId.trim()); }
        sql.append(" ORDER BY audit_id DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 1000)));
        List<Map<String,Object>> rows = db.queryForList(sql.toString(), args.toArray());
        for (Map<String,Object> row : rows) {
            row.put("oldValue", parseJson(row.get("oldValue")));
            row.put("newValue", parseJson(row.get("newValue")));
            row.put("changedFields", parseJson(row.get("changedFields")));
        }
        return rows;
    }

    private Object parseJson(Object value) {
        if (value == null) return null;
        try { return json.readTree(value.toString()); }
        catch (Exception ignored) { return value.toString(); }
    }
}
