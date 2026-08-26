package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.model.DocumentServiceRegistration;
import org.gepnic.doors.masterapi.repository.AgentRepository;
import org.gepnic.doors.masterapi.repository.DocumentServiceRegistrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DocumentServiceRegistryService {

    private final DocumentServiceRegistrationRepository repository;
    private final AgentRepository agentRepository;
    private final org.gepnic.doors.masterapi.repository.ApiClientRepository apiClientRepository;
    private final org.gepnic.doors.masterapi.repository.SqlTemplateRepository sqlTemplateRepository;
    private final org.gepnic.doors.masterapi.repository.DocumentDownloadPolicyRepository policyRepository;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return repository.findAllByOrderByServiceNameAsc().stream().map(this::toMap).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> options() {
        List<Map<String, Object>> clients = apiClientRepository.findByIsActiveTrueOrderByClientNameAsc().stream()
                .map(client -> Map.<String, Object>of(
                        "clientId", client.getClientId(),
                        "clientName", client.getClientName(),
                        "apiKey", client.getApiKey()))
                .toList();
        List<Map<String, Object>> queries = sqlTemplateRepository.findByStatusAndIsActive("APPROVED", true).stream()
                .sorted(Comparator.comparing(org.gepnic.doors.masterapi.model.SqlTemplate::getUniqueName,
                        String.CASE_INSENSITIVE_ORDER))
                .map(query -> Map.<String, Object>of(
                        "queryId", query.getQueryId(),
                        "uniqueName", query.getUniqueName(),
                        "parameters", query.getParameters()))
                .toList();
        List<Map<String, Object>> policies = policyRepository.findAllByOrderByPolicyNameAsc().stream()
                .filter(policy -> "ACTIVE".equals(policy.getStatus()))
                .map(policy -> Map.<String, Object>of(
                        "policyId", policy.getPolicyId(),
                        "policyCode", policy.getPolicyCode(),
                        "policyName", policy.getPolicyName()))
                .toList();
        return Map.of("clients", clients, "queries", queries, "policies", policies);
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        DocumentServiceRegistration registration = new DocumentServiceRegistration();
        apply(registration, body, true);
        if (repository.existsByAgentId(registration.getAgentId())) {
            throw new IllegalArgumentException("A document service is already registered for Agent " + registration.getAgentId());
        }
        if (repository.existsByServiceNameIgnoreCase(registration.getServiceName())) {
            throw new IllegalArgumentException("DOCSServiceName is already registered");
        }
        validate(registration);
        return toMap(repository.save(registration));
    }

    @Transactional
    public Map<String, Object> update(Long id, Map<String, Object> body) {
        DocumentServiceRegistration registration = require(id);
        apply(registration, body, false);
        if (repository.existsByAgentIdAndServiceIdNot(registration.getAgentId(), id)) {
            throw new IllegalArgumentException("A document service is already registered for Agent " + registration.getAgentId());
        }
        if (repository.existsByServiceNameIgnoreCaseAndServiceIdNot(registration.getServiceName(), id)) {
            throw new IllegalArgumentException("DOCSServiceName is already registered");
        }
        validate(registration);
        return toMap(repository.save(registration));
    }

    @Transactional
    public Map<String, Object> setActive(Long id, boolean active) {
        DocumentServiceRegistration registration = require(id);
        registration.setIsActive(active);
        return toMap(repository.save(registration));
    }

    private void apply(DocumentServiceRegistration value, Map<String, Object> body, boolean creating) {
        if (creating || body.containsKey("agentId")) value.setAgentId(required(body.get("agentId"), "agentId"));
        if (creating || body.containsKey("serviceName")) value.setServiceName(required(body.get("serviceName"), "serviceName"));
        if (creating || body.containsKey("baseUrl")) value.setBaseUrl(required(body.get("baseUrl"), "baseUrl"));
        if (body.containsKey("downloadPath")) value.setDownloadPath(required(body.get("downloadPath"), "downloadPath"));
        if (body.containsKey("manifestQueryName")) value.setManifestQueryName(optional(body.get("manifestQueryName")));
        if (body.containsKey("manifestClientName")) value.setManifestClientName(optional(body.get("manifestClientName")));
        if (creating || body.containsKey("documentDownloadPolicyCode")) value.setDocumentDownloadPolicyCode(
                required(body.get("documentDownloadPolicyCode"), "documentDownloadPolicyCode"));
        if (body.containsKey("accessMode")) value.setAccessMode(required(body.get("accessMode"), "accessMode").toUpperCase(Locale.ROOT));
        if (body.containsKey("connectTimeoutMs")) value.setConnectTimeoutMs(integer(body.get("connectTimeoutMs"), "connectTimeoutMs"));
        if (body.containsKey("readTimeoutMs")) value.setReadTimeoutMs(integer(body.get("readTimeoutMs"), "readTimeoutMs"));
        if (body.containsKey("verifyTls")) value.setVerifyTls(Boolean.valueOf(String.valueOf(body.get("verifyTls"))));
        if (body.containsKey("isActive")) value.setIsActive(Boolean.valueOf(String.valueOf(body.get("isActive"))));
        value.setBaseUrl(value.getBaseUrl().replaceAll("/+$", ""));
        if (!value.getDownloadPath().startsWith("/")) value.setDownloadPath("/" + value.getDownloadPath());
    }

    private void validate(DocumentServiceRegistration value) {
        if (!agentRepository.existsById(value.getAgentId())) throw new IllegalArgumentException("Unknown Agent: " + value.getAgentId());
        if (!value.getServiceName().matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("DOCSServiceName may contain only letters, numbers, hyphen and underscore");
        }
        var policy = policyRepository.findByPolicyCodeIgnoreCase(value.getDocumentDownloadPolicyCode())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown Document Download Policy: " + value.getDocumentDownloadPolicyCode()));
        if (!"ACTIVE".equals(policy.getStatus())) {
            throw new IllegalArgumentException("Document Download Policy must be active");
        }
        boolean queryMapped = value.getManifestQueryName() != null;
        boolean clientMapped = value.getManifestClientName() != null;
        if (queryMapped != clientMapped) {
            throw new IllegalArgumentException("Manifest Query Name and Manifest ClientName must be configured together");
        }
        if (queryMapped) {
            var template = sqlTemplateRepository.findByUniqueName(value.getManifestQueryName())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown manifest query: " + value.getManifestQueryName()));
            if (!"APPROVED".equals(template.getStatus()) || !Boolean.TRUE.equals(template.getIsActive())) {
                throw new IllegalArgumentException("Manifest query must be approved and active");
            }
            if (!template.getAuthorizedAgents().contains(value.getAgentId())) {
                throw new IllegalArgumentException("Manifest query is not authorized for Agent " + value.getAgentId());
            }
            apiClientRepository.findByClientNameIgnoreCaseAndIsActiveTrue(value.getManifestClientName())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown or inactive manifest ClientName: " + value.getManifestClientName()));
        }
        if (!Set.of("MASTER_DIRECT", "AGENT_PROXY").contains(value.getAccessMode())) throw new IllegalArgumentException("Access mode must be MASTER_DIRECT or AGENT_PROXY");
        try {
            URI uri = URI.create(value.getBaseUrl());
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null) throw new IllegalArgumentException();
        } catch (Exception exception) {
            throw new IllegalArgumentException("Document service base URL must be an absolute HTTP(S) URL");
        }
        if (Boolean.TRUE.equals(value.getVerifyTls()) && !value.getBaseUrl().startsWith("https://")) {
            throw new IllegalArgumentException("TLS verification requires an HTTPS document-service URL");
        }
        if (value.getConnectTimeoutMs() < 1000 || value.getConnectTimeoutMs() > 60000) throw new IllegalArgumentException("Connect timeout must be between 1000 and 60000 ms");
        if (value.getReadTimeoutMs() < 1000 || value.getReadTimeoutMs() > 600000) throw new IllegalArgumentException("Read timeout must be between 1000 and 600000 ms");
    }

    private DocumentServiceRegistration require(Long id) {
        return repository.findById(id).orElseThrow(() -> new NoSuchElementException("Unknown document service: " + id));
    }

    private Map<String, Object> toMap(DocumentServiceRegistration value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serviceId", value.getServiceId());
        result.put("agentId", value.getAgentId());
        result.put("serviceName", value.getServiceName());
        result.put("baseUrl", value.getBaseUrl());
        result.put("downloadPath", value.getDownloadPath());
        result.put("manifestQueryName", value.getManifestQueryName());
        result.put("manifestClientName", value.getManifestClientName());
        result.put("documentDownloadPolicyCode", value.getDocumentDownloadPolicyCode());
        result.put("accessMode", value.getAccessMode());
        result.put("connectTimeoutMs", value.getConnectTimeoutMs());
        result.put("readTimeoutMs", value.getReadTimeoutMs());
        result.put("verifyTls", value.getVerifyTls());
        result.put("isActive", value.getIsActive());
        result.put("lockVersion", value.getLockVersion());
        result.put("updatedAt", value.getUpdatedAt());
        return result;
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

    private int integer(Object value, String field) {
        try { return Integer.parseInt(required(value, field)); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException(field + " must be an integer"); }
    }
}
