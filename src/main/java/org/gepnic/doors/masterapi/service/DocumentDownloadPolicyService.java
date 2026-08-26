package org.gepnic.doors.masterapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.model.DocumentDownloadPolicy;
import org.gepnic.doors.masterapi.repository.DocumentDownloadPolicyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class DocumentDownloadPolicyService {

    private static final Set<String> IDENTIFIERS = Set.of("bidId", "tenderId", "workItemRefNo", "documentType");
    private static final Set<String> STATUSES = Set.of("DRAFT", "TESTED", "ACTIVE", "INACTIVE", "RETIRED");
    private static final Pattern POLICY_CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,99}");
    private static final Pattern FUNCTION_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");
    private static final Pattern FORBIDDEN_SQL = Pattern.compile(
            "(?is)\\b(insert|update|delete|merge|drop|alter|truncate|create|grant|revoke|call|execute|copy)\\b|--|/\\*");

    private final DocumentDownloadPolicyRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return repository.findAllByOrderByPolicyNameAsc().stream().map(this::toMap).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(Long id) {
        return toMap(require(id));
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        DocumentDownloadPolicy policy = new DocumentDownloadPolicy();
        apply(policy, body, true);
        if (repository.existsByPolicyCodeIgnoreCase(policy.getPolicyCode())) {
            throw new IllegalArgumentException("Policy code already exists: " + policy.getPolicyCode());
        }
        validate(policy);
        return toMap(repository.save(policy));
    }

    @Transactional
    public Map<String, Object> update(Long id, Map<String, Object> body) {
        DocumentDownloadPolicy policy = require(id);
        if (Set.of("ACTIVE", "RETIRED").contains(policy.getStatus())) {
            throw new IllegalStateException("Active or retired policies cannot be edited; deactivate the policy first");
        }
        apply(policy, body, false);
        policy.setStatus("DRAFT");
        policy.setPolicyVersion(policy.getPolicyVersion() + 1);
        validate(policy);
        return toMap(repository.save(policy));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> validatePolicy(Long id) {
        DocumentDownloadPolicy policy = require(id);
        validate(policy);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", true);
        result.put("policyId", policy.getPolicyId());
        result.put("policyCode", policy.getPolicyCode());
        result.put("documentTypeCount", policy.getDocumentTypes().size());
        result.put("message", "Policy structure is valid. Runtime Agent dry-run has not yet been performed.");
        return result;
    }

    @Transactional
    public Map<String, Object> activate(Long id) {
        DocumentDownloadPolicy policy = require(id);
        validate(policy);
        if (!Set.of("DRAFT", "TESTED", "INACTIVE").contains(policy.getStatus())) {
            throw new IllegalStateException("Policy cannot be activated from status " + policy.getStatus());
        }
        policy.setStatus("ACTIVE");
        return toMap(repository.save(policy));
    }

    @Transactional
    public Map<String, Object> deactivate(Long id) {
        DocumentDownloadPolicy policy = require(id);
        if (!"ACTIVE".equals(policy.getStatus())) {
            throw new IllegalStateException("Only an active policy can be deactivated");
        }
        policy.setStatus("INACTIVE");
        return toMap(repository.save(policy));
    }

    private void apply(DocumentDownloadPolicy policy, Map<String, Object> body, boolean creating) {
        if (creating || body.containsKey("policyCode")) policy.setPolicyCode(text(body.get("policyCode")).toUpperCase(Locale.ROOT));
        if (creating || body.containsKey("policyName")) policy.setPolicyName(text(body.get("policyName")));
        if (body.containsKey("description")) policy.setDescription(optionalText(body.get("description")));
        if (creating || body.containsKey("executionMode")) policy.setExecutionMode(text(body.get("executionMode")).toUpperCase(Locale.ROOT));
        if (body.containsKey("functionName")) policy.setFunctionName(optionalText(body.get("functionName")));
        if (body.containsKey("eligibilitySql")) policy.setEligibilitySql(optionalText(body.get("eligibilitySql")));
        if (body.containsKey("decisionColumn")) policy.setDecisionColumn(text(body.get("decisionColumn")));
        if (body.containsKey("allowedValue")) policy.setAllowedValue(text(body.get("allowedValue")));
        policy.setAcceptedIdentifiers(jsonArray(body.get("acceptedIdentifiers"), policy.getAcceptedIdentifiers()));
        policy.setDocumentTypes(jsonArray(body.get("documentTypes"), policy.getDocumentTypes()));
        policy.setAuthorizedClientIds(jsonArray(body.get("authorizedClientIds"), policy.getAuthorizedClientIds()));
        if (creating) policy.setStatus("DRAFT");
        if ("FUNCTION".equals(policy.getExecutionMode())) policy.setEligibilitySql(null);
        if ("QUERY".equals(policy.getExecutionMode())) policy.setFunctionName(null);
    }

    private void validate(DocumentDownloadPolicy policy) {
        if (!POLICY_CODE.matcher(policy.getPolicyCode()).matches()) {
            throw new IllegalArgumentException("Policy code must use uppercase letters, numbers and underscores");
        }
        if (policy.getPolicyName() == null || policy.getPolicyName().isBlank()) throw new IllegalArgumentException("Policy name is required");
        if (!Set.of("FUNCTION", "QUERY").contains(policy.getExecutionMode())) throw new IllegalArgumentException("Execution mode must be FUNCTION or QUERY");
        if (!STATUSES.contains(policy.getStatus())) throw new IllegalArgumentException("Unsupported policy status");
        if ("FUNCTION".equals(policy.getExecutionMode())) {
            if (policy.getFunctionName() == null || !FUNCTION_NAME.matcher(policy.getFunctionName()).matches()) {
                throw new IllegalArgumentException("An approved schema-qualified function name is required");
            }
        } else validateSql(policy.getEligibilitySql());

        requireArray(policy.getAcceptedIdentifiers(), "acceptedIdentifiers");
        if (policy.getAcceptedIdentifiers().isEmpty()) throw new IllegalArgumentException("At least one accepted identifier is required");
        policy.getAcceptedIdentifiers().forEach(value -> {
            if (!IDENTIFIERS.contains(value.asText())) throw new IllegalArgumentException("Unsupported identifier: " + value.asText());
        });

        requireArray(policy.getDocumentTypes(), "documentTypes");
        if (policy.getDocumentTypes().isEmpty()) throw new IllegalArgumentException("At least one document type mapping is required");
        Set<String> businessTypes = new HashSet<>();
        policy.getDocumentTypes().forEach(type -> {
            String businessType = requiredField(type, "businessType", "Document type").toUpperCase(Locale.ROOT);
            if (!businessTypes.add(businessType)) throw new IllegalArgumentException("Duplicate document type: " + businessType);
            requiredField(type, "serviceDocCode", "Document type " + businessType);
            requiredField(type, "packetType", "Document type " + businessType);
        });
        requireArray(policy.getAuthorizedClientIds(), "authorizedClientIds");
    }

    private void validateSql(String sql) {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("Eligibility SQL is required");
        String normalized = sql.strip();
        if (!(normalized.regionMatches(true, 0, "SELECT", 0, 6) || normalized.regionMatches(true, 0, "WITH", 0, 4))) {
            throw new IllegalArgumentException("Eligibility SQL must be a SELECT query");
        }
        String withoutTrailingSemicolon = normalized.endsWith(";") ? normalized.substring(0, normalized.length() - 1) : normalized;
        if (withoutTrailingSemicolon.contains(";")) throw new IllegalArgumentException("Multiple SQL statements are not allowed");
        if (FORBIDDEN_SQL.matcher(withoutTrailingSemicolon).find()) throw new IllegalArgumentException("Eligibility SQL contains a prohibited operation or comment");
    }

    private DocumentDownloadPolicy require(Long id) {
        return repository.findById(id).orElseThrow(() -> new NoSuchElementException("Unknown document policy: " + id));
    }

    private Map<String, Object> toMap(DocumentDownloadPolicy policy) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("policyId", policy.getPolicyId());
        result.put("policyCode", policy.getPolicyCode());
        result.put("policyName", policy.getPolicyName());
        result.put("description", policy.getDescription());
        result.put("executionMode", policy.getExecutionMode());
        result.put("functionName", policy.getFunctionName());
        result.put("eligibilitySql", policy.getEligibilitySql());
        result.put("acceptedIdentifiers", policy.getAcceptedIdentifiers());
        result.put("decisionColumn", policy.getDecisionColumn());
        result.put("allowedValue", policy.getAllowedValue());
        result.put("documentTypes", policy.getDocumentTypes());
        result.put("authorizedClientIds", policy.getAuthorizedClientIds());
        result.put("status", policy.getStatus());
        result.put("policyVersion", policy.getPolicyVersion());
        result.put("lockVersion", policy.getLockVersion());
        result.put("createdBy", policy.getCreatedBy());
        result.put("createdAt", policy.getCreatedAt());
        result.put("updatedBy", policy.getUpdatedBy());
        result.put("updatedAt", policy.getUpdatedAt());
        return result;
    }

    private JsonNode jsonArray(Object value, JsonNode current) {
        if (value == null) return current == null ? objectMapper.createArrayNode() : current;
        JsonNode node = objectMapper.valueToTree(value);
        requireArray(node, "JSON value");
        return node;
    }

    private void requireArray(JsonNode node, String field) {
        if (node == null || !node.isArray()) throw new IllegalArgumentException(field + " must be an array");
    }

    private String requiredField(JsonNode node, String field, String context) {
        String value = node.path(field).asText("").trim();
        if (value.isBlank()) throw new IllegalArgumentException(context + " requires " + field);
        return value;
    }

    private String text(Object value) {
        String result = optionalText(value);
        if (result == null) throw new IllegalArgumentException("Required value is missing");
        return result;
    }

    private String optionalText(Object value) {
        if (value == null) return null;
        String result = String.valueOf(value).trim();
        return result.isEmpty() ? null : result;
    }
}
