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

        DocumentPolicyAccess.requireClient(policy, client);
        Map<String, Object> eligibility = objectMap(request.get("eligibility"), "eligibility");
        Map<String, Object> document = objectMap(request.get("document"), "document");
        String documentType = required(document.get("documentType"), "document.documentType").toUpperCase(Locale.ROOT);
        JsonNode mapping = findMapping(policy, documentType);

        Map<String, Object> params = new LinkedHashMap<>(eligibility);
        params.put("authenticatedClientId", client.getClientId());
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

        DocumentServiceRegistration registration = resolveDocumentService(agentId, document);
        RegisteredDocumentServiceClient.DocumentPayload response = documentServiceClient.download(
                registration, downloadId, serviceDocCode, fileName, packetType);
        return new DownloadedDocument(fileName, response.contentType(), response.content(),
                response.contentLength(), response.sha256(), agentId, policy.getPolicyCode(), documentType);
    }

    private void authorizeAgent(ApiClient client, String agentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_authorized_agents WHERE user_name = ? AND agent_id = ?",
                Integer.class, client.getClientName(), agentId);
        if (count == null || count == 0) throw new SecurityException("API Client is not authorized for Agent " + agentId);
    }

    public void authorizeRegisteredDocument(ApiClient client, DocumentServiceRegistration registration,
            Map<String, Object> document, Map<String, Object> approvedParameters) {
        if (registration.getDocumentDownloadPolicyCode() == null) return;
        var policy = policyRepository.findByPolicyCodeIgnoreCase(registration.getDocumentDownloadPolicyCode())
                .orElseThrow(() -> new SecurityException("Unknown document policy"));
        DocumentPolicyAccess.requireClient(policy, client);
        var mapping = findMapping(policy, required(approvedParameters.get("documentType"), "documentType"));
        if (!mapping.path("serviceDocCode").asText().equals(document.get("docCode"))
                || !mapping.path("packetType").asText().equals(document.get("packetType")))
            throw new SecurityException("Document does not match the policy mapping");
        Map<String, Object> params = new LinkedHashMap<>(approvedParameters);
        params.put("authenticatedClientId", client.getClientId());
        params.put("downloadId", document.get("downloadId"));
        params.put("fileName", document.get("fileName"));
        var decision = agentExecutionService.evaluateDocumentEligibility(registration.getAgentId(), policy, params);
        if (!Boolean.TRUE.equals(decision.get("success")) || !"ALLOW".equals(decision.get("decision")))
            throw new SecurityException("Document policy denied this document");
        var result = objectMap(decision.get("result"), "eligibility result");
        resultValueOrDefault(result, mapping.path("downloadIdColumn").asText("download_id"),
                required(document.get("downloadId"), "downloadId"));
        resultValueOrDefault(result, mapping.path("fileNameColumn").asText("file_name"),
                required(document.get("fileName"), "fileName"));
    }

    private DocumentServiceRegistration resolveDocumentService(String agentId, Map<String, Object> document) {
        String requestedServiceName = optional(document.get("docsServiceName"));
        if (requestedServiceName != null) {
            DocumentServiceRegistration registration = serviceRepository
                    .findByServiceNameIgnoreCase(requestedServiceName)
                    .filter(value -> Boolean.TRUE.equals(value.getIsActive()))
                    .orElseThrow(() -> new NoSuchElementException(
                            "Active document service not found: " + requestedServiceName));
            if (!agentId.equals(registration.getAgentId())) {
                throw new IllegalArgumentException(
                        "Document service " + requestedServiceName + " is not registered for Agent " + agentId);
            }
            return registration;
        }

        List<DocumentServiceRegistration> registrations = serviceRepository
                .findAllByAgentIdAndIsActiveTrueOrderByServiceNameAsc(agentId);
        if (registrations.isEmpty()) {
            throw new NoSuchElementException("No active document service is registered for Agent " + agentId);
        }
        if (registrations.size() > 1) {
            throw new IllegalArgumentException(
                    "Multiple active document services are registered for Agent " + agentId
                            + "; document.docsServiceName is required");
        }
        return registrations.getFirst();
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
        if (value == null || String.valueOf(value).isBlank())
            throw new SecurityException("Eligibility must resolve the exact document: " + column);
        if (!defaultValue.equals(String.valueOf(value)))
            throw new SecurityException("Eligibility resolved a different document");
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

    private String optional(Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    public record DownloadedDocument(String fileName, MediaType contentType, java.nio.file.Path content,
                                     long contentLength, String sha256, String agentId,
                                     String policyCode, String documentType) implements AutoCloseable {
        public java.io.InputStream openStream() throws java.io.IOException {
            return java.nio.file.Files.newInputStream(content);
        }
        @Override public void close() {
            try { java.nio.file.Files.deleteIfExists(content); }
            catch (java.io.IOException e) { throw new IllegalStateException("Unable to remove temporary document", e); }
        }
    }
}
