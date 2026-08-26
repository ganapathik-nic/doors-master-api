package org.gepnic.doors.masterapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.model.DocumentDownloadPolicy;
import org.gepnic.doors.masterapi.model.DocumentServiceRegistration;
import org.gepnic.doors.masterapi.repository.ApiClientRepository;
import org.gepnic.doors.masterapi.repository.DocumentDownloadPolicyRepository;
import org.gepnic.doors.masterapi.repository.DocumentServiceRegistrationRepository;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class DocumentDownloadOrchestrationService {

    private final ApiClientRepository clientRepository;
    private final DocumentDownloadPolicyRepository policyRepository;
    private final DocumentServiceRegistrationRepository serviceRepository;
    private final AgentExecutionService agentExecutionService;
    private final JdbcTemplate jdbcTemplate;
    private final RegisteredDocumentServiceClient documentServiceClient;

    public DownloadedDocument download(String policyCode, String apiKey, Map<String, Object> request) {
        ApiClient client = clientRepository.findByApiKey(apiKey)
                .filter(value -> Boolean.TRUE.equals(value.getIsActive()))
                .orElseThrow(() -> new SecurityException("API Client credentials are invalid or inactive"));
        String agentId = required(request.get("agentId"), "agentId");
        authorizeAgent(client, agentId);
        DocumentDownloadPolicy policy = policyRepository.findByPolicyCodeIgnoreCase(policyCode)
                .filter(value -> "ACTIVE".equals(value.getStatus()))
                .orElseThrow(() -> new NoSuchElementException("Active document policy not found: " + policyCode));

        Map<String, Object> eligibility = objectMap(request.get("eligibility"), "eligibility");
        Map<String, Object> document = objectMap(request.get("document"), "document");
        String documentType = required(document.get("documentType"), "document.documentType").toUpperCase(Locale.ROOT);
        JsonNode mapping = findMapping(policy, documentType);

        Map<String, Object> params = new LinkedHashMap<>(eligibility);
        params.put("documentType", documentType);
        params.put("downloadId", required(document.get("downloadId"), "document.downloadId"));
        params.put("fileName", safeFileName(required(document.get("fileName"), "document.fileName")));
        Map<String, Object> decision = agentExecutionService.evaluateDocumentEligibility(agentId, policy, params);
        if (!Boolean.TRUE.equals(decision.get("success")) || !"ALLOW".equals(decision.get("decision"))) {
            throw new SecurityException("Document download is not permitted by the active policy");
        }
        Map<String, Object> result = objectMap(decision.get("result"), "eligibility result");
        String requestedDownloadId = required(document.get("downloadId"), "document.downloadId");
        String requestedFileName = safeFileName(required(document.get("fileName"), "document.fileName"));
        String downloadId = resultValueOrDefault(result,
                mapping.path("downloadIdColumn").asText("download_id"), requestedDownloadId);
        String fileName = safeFileName(resultValueOrDefault(result,
                mapping.path("fileNameColumn").asText("file_name"), requestedFileName));
        String serviceDocCode = required(mapping.path("serviceDocCode").asText(null), "serviceDocCode mapping");
        String packetType = required(mapping.path("packetType").asText(null), "packetType mapping");

        DocumentServiceRegistration registration = serviceRepository.findByAgentId(agentId)
                .filter(value -> Boolean.TRUE.equals(value.getIsActive()))
                .orElseThrow(() -> new NoSuchElementException("No active document service is registered for Agent " + agentId));
        RegisteredDocumentServiceClient.DocumentPayload response = documentServiceClient.download(
                registration, downloadId, serviceDocCode, fileName, packetType);
        return new DownloadedDocument(fileName,
                response.contentType(), response.content(), agentId, policy.getPolicyCode(), documentType);
    }

    private void authorizeAgent(ApiClient client, String agentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_authorized_agents WHERE user_name = ? AND agent_id = ?",
                Integer.class, client.getClientName(), agentId);
        if (count == null || count == 0) throw new SecurityException("API Client is not authorized for Agent " + agentId);
    }

    private JsonNode findMapping(DocumentDownloadPolicy policy, String documentType) {
        for (JsonNode mapping : policy.getDocumentTypes()) {
            if (documentType.equalsIgnoreCase(mapping.path("businessType").asText())) return mapping;
        }
        throw new IllegalArgumentException("Document type is not enabled by this policy: " + documentType);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value, String field) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException(field + " must be an object");
        return new LinkedHashMap<>((Map<String, Object>) value);
    }

    private String resultValueOrDefault(Map<String, Object> result, String column, String defaultValue) {
        Object value = result.get(column.toLowerCase(Locale.ROOT));
        if (value == null || String.valueOf(value).isBlank()) return defaultValue;
        return required(value, "Eligibility result column " + column);
    }

    private String safeFileName(String value) {
        String file = required(value, "fileName");
        if (!file.equals(java.nio.file.Path.of(file).getFileName().toString()) || file.contains("..")) {
            throw new IllegalArgumentException("Invalid document filename");
        }
        return file;
    }

    private String required(Object value, String field) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isBlank()) throw new IllegalArgumentException(field + " is required");
        return text;
    }

    public record DownloadedDocument(String fileName, MediaType contentType, byte[] content,
                                     String agentId, String policyCode, String documentType) { }
}
