package org.gepnic.doors.masterapi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConfigurationAuditService {
    private static final Set<String> SECRET_NAMES = Set.of(
            "password", "passwordhash", "temporarypassword", "secret", "transitsecret",
            "privatekey", "encryptedkey", "securedata", "signature",
            "mfasecret", "mfasecretencrypted", "targetdbpassword", "walletpass",
            "truststorepass", "keypassword");
    private static final Set<String> PUBLIC_KEY_NAMES = Set.of(
            "publickey", "clientpublickey", "certificate", "certificatepem", "apikey");

    private final JdbcTemplate db;
    private final ObjectMapper json;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Event event) {
        try {
            JsonNode oldValue = sanitize(event.oldValue());
            JsonNode newValue = sanitize(event.newValue());
            db.update("""
                    INSERT INTO configuration_audit_events
                    (actor_username,actor_role,action,entity_type,entity_id,entity_name,
                     old_value,new_value,changed_fields,justification,client_ip,session_id,
                     trace_id,endpoint,http_method,http_status,outcome,failure_code,failure_message)
                    VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?,?,?,?,?,?,?,?,?)
                    """,
                    value(event.actor(), "SYSTEM"), event.actorRole(), event.action(),
                    value(event.entityType(), "CONFIGURATION"), event.entityId(), event.entityName(),
                    text(oldValue), text(newValue), text(changedFields(oldValue, newValue)),
                    event.justification(), event.clientIp(), event.sessionId(), event.traceId(),
                    event.endpoint(), event.method(), event.status(), event.outcome(),
                    event.failureCode(), event.failureMessage());
        } catch (Exception ex) {
            // Audit failure must be visible but must not replace the business response.
            log.error("CONFIGURATION_AUDIT_WRITE_FAILED trace={} action={}",
                    event.traceId(), event.action(), ex);
        }
    }

    public JsonNode sanitize(Object input) {
        if (input == null) return null;
        JsonNode node = json.valueToTree(input);
        return sanitizeNode(node, null);
    }

    private JsonNode sanitizeNode(JsonNode node, String fieldName) {
        if (node == null || node.isNull()) return node;
        String normalized = normalize(fieldName);
        if (PUBLIC_KEY_NAMES.stream().anyMatch(normalized::contains) && node.isTextual()) {
            return json.getNodeFactory().textNode("SHA-256:" + sha256(node.asText()));
        }
        if (SECRET_NAMES.stream().anyMatch(normalized::contains)) {
            return json.getNodeFactory().textNode("[REDACTED]");
        }
        if (node.isObject()) {
            var clean = json.createObjectNode();
            node.fields().forEachRemaining(entry ->
                    clean.set(entry.getKey(), sanitizeNode(entry.getValue(), entry.getKey())));
            return clean;
        }
        if (node.isArray()) {
            var clean = json.createArrayNode();
            node.forEach(item -> clean.add(sanitizeNode(item, fieldName)));
            return clean;
        }
        return node;
    }

    private JsonNode changedFields(JsonNode oldValue, JsonNode newValue) {
        var fields = json.createArrayNode();
        if (oldValue == null && newValue == null) return fields;
        if (oldValue == null || newValue == null || !oldValue.isObject() || !newValue.isObject()) {
            fields.add("*");
            return fields;
        }
        var names = new TreeSet<String>();
        oldValue.fieldNames().forEachRemaining(names::add);
        newValue.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            if (!Objects.equals(oldValue.get(name), newValue.get(name))) fields.add(name);
        }
        return fields;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).toUpperCase(Locale.ROOT);
        } catch (Exception ex) {
            return "UNAVAILABLE";
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private String text(JsonNode value) {
        return value == null ? null : value.toString();
    }

    private String value(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }

    public record Event(String actor, String actorRole, String action, String entityType,
                        String entityId, String entityName, Object oldValue, Object newValue,
                        String justification, String clientIp, String sessionId, String traceId,
                        String endpoint, String method, int status, String outcome,
                        String failureCode, String failureMessage) { }
}
