package org.gepnic.doors.masterapi.service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.gepnic.doors.masterapi.exception.DoorsApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GatewayProtocolService {
    private final JdbcTemplate jdbc;
    public GatewayProtocolService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public java.util.List<Map<String,Object>> policies(long clientId) {
        return jdbc.queryForList("SELECT DISTINCT t.query_id AS \"queryId\", t.unique_name AS \"queryName\", v.version, v.label, v.enabled AS \"available\", COALESCE(p.enabled,FALSE) AS enabled, COALESCE(p.deprecated,FALSE) AS deprecated, p.retire_at AS \"retireAt\", p.last_used_at AS \"lastUsedAt\" FROM external_client_query_map m JOIN sql_templates t ON t.query_id=m.query_id CROSS JOIN gateway_protocol_catalog v LEFT JOIN gateway_query_protocol p ON p.client_id=m.client_id AND p.query_id=m.query_id AND p.version=v.version WHERE m.client_id=? ORDER BY t.query_id,v.version", clientId);
    }

    @Transactional
    public void updatePolicy(long clientId, long queryId, int version, boolean enabled,
                             boolean deprecated, Instant retireAt, String actor) {
        Integer mappings=jdbc.queryForObject("SELECT count(*) FROM external_client_query_map WHERE client_id=? AND query_id=?",Integer.class,clientId,queryId);
        if (mappings == null || mappings == 0) throw new SecurityException("Query is not assigned to this client");
        // Catalogue entries describe implemented handlers; configuration cannot create executable code.
        var registered=jdbc.queryForList("SELECT handler FROM gateway_protocol_catalog WHERE version=?",String.class,version);
        if (registered.size()!=1 || (!"LEGACY".equals(registered.get(0)) && !"SIGNED_FRESHNESS".equals(registered.get(0))))
            throw invalid("DOORS-PROTOCOL-UNSUPPORTED",HttpStatus.BAD_REQUEST);
        jdbc.update("INSERT INTO gateway_query_protocol(client_id,query_id,version) VALUES (?,?,?) ON CONFLICT DO NOTHING",clientId,queryId,version);
        String previous=jdbc.queryForObject("SELECT row_to_json(p)::text FROM gateway_query_protocol p WHERE client_id=? AND query_id=? AND version=? FOR UPDATE",String.class,clientId,queryId,version);
        jdbc.update("UPDATE gateway_query_protocol SET enabled=?,deprecated=?,retire_at=? WHERE client_id=? AND query_id=? AND version=?",enabled,deprecated,retireAt==null?null:java.sql.Timestamp.from(retireAt),clientId,queryId,version);
        jdbc.update("INSERT INTO gateway_protocol_policy_audit(client_id,query_id,version,changed_by,old_policy,new_policy) SELECT client_id,query_id,version,?,?::jsonb,row_to_json(p)::jsonb FROM gateway_query_protocol p WHERE client_id=? AND query_id=? AND version=?",actor,previous,clientId,queryId,version);
    }

    public String requireAllowed(long clientId, String query, int version) {
        var handlers=jdbc.queryForList("SELECT v.handler FROM gateway_query_protocol p JOIN gateway_protocol_catalog v ON v.version=p.version JOIN sql_templates t ON t.query_id=p.query_id WHERE p.client_id=? AND t.unique_name=? AND p.version=? AND p.enabled AND v.enabled AND (p.retire_at IS NULL OR p.retire_at>clock_timestamp()) AND EXISTS(SELECT 1 FROM external_client_query_map m WHERE m.client_id=p.client_id AND m.query_id=p.query_id)",String.class,clientId,query,version);
        if(handlers.size()!=1 || (!"LEGACY".equals(handlers.get(0)) && !"SIGNED_FRESHNESS".equals(handlers.get(0))))
            throw invalid("DOORS-PROTOCOL-DISABLED",HttpStatus.BAD_REQUEST);
        return handlers.get(0);
    }

    public static DoorsApiException invalid(String code, HttpStatus status) {
        return new DoorsApiException(status, code, "invalid-secure-request", "Secure request rejected",
                "The secure request is invalid, expired, duplicated or uses an unsupported protocol.", false, Map.of());
    }

    // Called ONLY after signature verification. REQUIRES_NEW keeps the claim even if
    // downstream execution fails; duplicates are rejected, never re-executed automatically.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void validateAndClaim(long clientId, String query, int outerVersion,
                                 boolean encrypted, Map<String,Object> payload) {
        String handler = requireAllowed(clientId,query,outerVersion);
        Object inner = payload.get("protocolVersion");
        boolean v2 = "SIGNED_FRESHNESS".equals(handler);
        if (!v2) {
            if (inner != null) throw invalid("DOORS-PROTOCOL-INVALID", HttpStatus.BAD_REQUEST);
            markUsed(clientId,query,outerVersion);
            return; // Explicit legacy compatibility; not replay-protected.
        }
        if (!encrypted || !Integer.valueOf(outerVersion).equals(inner)
                || !Long.toString(clientId).equals(String.valueOf(payload.get("clientId")))
                || !query.equals(payload.get("query")))
            throw invalid("DOORS-PROTOCOL-INVALID", HttpStatus.BAD_REQUEST);
        UUID id;
        Instant issued;
        try {
            String raw = (String) payload.get("requestId");
            id = UUID.fromString(raw);
            if (!id.toString().equals(raw)) throw new IllegalArgumentException();
            issued = Instant.parse((String) payload.get("issuedAt"));
        } catch (RuntimeException e) { throw invalid("DOORS-PROTOCOL-INVALID", HttpStatus.BAD_REQUEST); }
        Instant now = jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant();
        if (issued.isBefore(now.minusSeconds(300)) || issued.isAfter(now.plusSeconds(30)))
            throw invalid("DOORS-REQUEST-EXPIRED", HttpStatus.BAD_REQUEST);
        jdbc.update("DELETE FROM gateway_request_claim WHERE expires_at < clock_timestamp()");
        int inserted = jdbc.update("INSERT INTO gateway_request_claim(client_id,request_id,expires_at) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                clientId, id, java.sql.Timestamp.from(issued.plusSeconds(360)));
        if (inserted != 1) throw invalid("DOORS-REQUEST-REPLAY", HttpStatus.CONFLICT);
        markUsed(clientId,query,outerVersion);
    }

    private void markUsed(long clientId,String query,int version) {
        jdbc.update("UPDATE gateway_query_protocol SET last_used_at=clock_timestamp() WHERE client_id=? AND query_id=(SELECT query_id FROM sql_templates WHERE unique_name=?) AND version=?",clientId,query,version);
    }
}
