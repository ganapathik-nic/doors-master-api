package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.model.DocumentServiceRegistration;
import org.gepnic.doors.masterapi.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Shared database grants preserve the existing manifest/download HTTP contract across replicas. */
@Service
@RequiredArgsConstructor
public class DocumentGrantService {
    private final JdbcTemplate jdbc;
    private final SqlTemplateRepository templates;
    private final AgentRepository agents;
    private final DocumentDownloadPolicyRepository policies;

    public String currentScope(ApiClient client, DocumentServiceRegistration registration) {
        if (!Boolean.TRUE.equals(client.getIsActive()) || !Boolean.TRUE.equals(registration.getIsActive())
                || !Objects.equals(client.getClientName(), registration.getManifestClientName()))
            throw new SecurityException("Document service access denied");
        var template = templates.findByUniqueName(registration.getManifestQueryName())
                .filter(t -> "APPROVED".equals(t.getStatus()) && Boolean.TRUE.equals(t.getIsActive())
                        && t.getAuthorizedAgents().contains(registration.getAgentId()))
                .orElseThrow(() -> new SecurityException("Manifest query is not authorized"));
        agents.findById(registration.getAgentId()).filter(a -> Boolean.TRUE.equals(a.getIsActive()))
                .orElseThrow(() -> new SecurityException("Document agent is inactive"));
        String policyState = "OPEN";
        if (registration.getDocumentDownloadPolicyCode() != null) {
            var policy = policies.findByPolicyCodeIgnoreCase(registration.getDocumentDownloadPolicyCode())
                    .orElseThrow(() -> new SecurityException("Unknown document policy"));
            DocumentPolicyAccess.requireClient(policy, client);
            policyState = policy.getPolicyCode() + ":" + policy.getLockVersion();
        }
        return hash(client.getClientName(), client.getApiKey(), registration.getServiceName(),
                registration.getBaseUrl(), registration.getDownloadPath(), registration.getAgentId(),
                registration.getManifestQueryName(), String.valueOf(registration.getLockVersion()),
                template.getSqlText(), String.valueOf(template.getUpdatedAt()), policyState);
    }

    public void issue(String scope, List<Map<String, Object>> documents, Map<String, Object> manifestParameters) {
        jdbc.update("DELETE FROM document_access_grants WHERE expires_at < CURRENT_TIMESTAMP");
        for (var document : documents) {
            String key = key(scope, document.get("downloadId"), document.get("serviceDocCode"),
                    document.get("fileName"), document.get("packetType"));
            Map<String, Object> eligibility = new LinkedHashMap<>(manifestParameters);
            for (String field : List.of("bidId", "workItemRefNo", "tenderId", "documentType")) {
                if (document.get(field) != null) eligibility.put(field, document.get(field));
            }
            try {
                String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(eligibility);
                jdbc.update("""
                        INSERT INTO document_access_grants (grant_key, expires_at, eligibility_json)
                        VALUES (?, CURRENT_TIMESTAMP + INTERVAL '5 minutes', CAST(? AS jsonb))
                        ON CONFLICT (grant_key) DO UPDATE SET expires_at = EXCLUDED.expires_at,
                            eligibility_json = EXCLUDED.eligibility_json
                        """, key, json);
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                throw new IllegalArgumentException("Invalid manifest parameters");
            }
        }
    }

    public Map<String, Object> require(String scope, Map<String, Object> request) {
        var rows = jdbc.queryForList("""
                SELECT CAST(eligibility_json AS text) AS params_json FROM document_access_grants
                WHERE grant_key = ? AND expires_at > CURRENT_TIMESTAMP
                """, key(scope, request.get("downloadId"), request.get("docCode"),
                request.get("fileName"), request.get("packetType")));
        if (rows.size() != 1) throw new SecurityException("Refresh the manifest before downloading this document");
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    String.valueOf(rows.getFirst().get("params_json")), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new SecurityException("Invalid document grant");
        }
    }

    private String key(String scope, Object downloadId, Object docCode, Object filename, Object packet) {
        return hash(scope, Objects.toString(downloadId, ""), Objects.toString(docCode, ""),
                Objects.toString(filename, ""), Objects.toString(packet, ""));
    }

    private static String hash(String... values) {
        StringBuilder encoded = new StringBuilder();
        for (String value : values) {
            String text = Objects.toString(value, "");
            encoded.append(text.length()).append(':').append(text);
        }
        return DigestUtils.sha256Hex(encoded.toString());
    }
}
