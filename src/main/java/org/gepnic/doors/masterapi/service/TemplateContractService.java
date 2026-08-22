package org.gepnic.doors.masterapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.media.Schema;
import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.model.SqlTemplate;
import org.gepnic.doors.masterapi.model.TemplateContract;
import org.gepnic.doors.masterapi.repository.SqlTemplateRepository;
import org.gepnic.doors.masterapi.repository.TemplateContractRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class TemplateContractService {

    private static final List<String> EDITABLE = List.of("DISCOVERED", "DRAFT", "REJECTED");
    private static final List<String> CANDIDATE = List.of("DISCOVERED", "DRAFT", "IN_REVIEW", "REJECTED");

    private final SqlTemplateRepository templateRepository;
    private final TemplateContractRepository contractRepository;
    private final ObjectMapper objectMapper;

    @Value("${doors.contracts.capture-observed-responses:false}")
    private boolean captureObservedResponses;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> publishedCatalogue() {
        return approvedTemplates().stream().map(this::toPublishedEntry).toList();
    }

    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> publishedContract(String uniqueName) {
        return approvedTemplate(uniqueName).map(this::toPublishedEntry);
    }

    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> exportPublishedContract(String uniqueName) {
        return approvedTemplate(uniqueName).flatMap(template ->
                contractRepository.findByTemplateQueryIdAndIsCurrentTrue(template.getQueryId())
                        .filter(contract -> "APPROVED".equals(contract.getContractStatus()))
                        .map(contract -> {
                            Map<String, Object> export = new LinkedHashMap<>();
                            export.put("contractFormatVersion", "1.0");
                            export.put("exportedAt", LocalDateTime.now());
                            export.put("template", Map.of(
                                    "uniqueName", template.getUniqueName(),
                                    "description", template.getDescription() == null ? "" : template.getDescription(),
                                    "category", template.getCategory() == null ? "" : template.getCategory(),
                                    "templateVersion", template.getVersion() == null ? 1 : template.getVersion()
                            ));
                            Map<String, Object> contractDetails = new LinkedHashMap<>();
                            contractDetails.put("version", contract.getContractVersion());
                            contractDetails.put("status", contract.getContractStatus());
                            contractDetails.put("publishedAt", contract.getPublishedAt());
                            contractDetails.put("schemaHash", contract.getSchemaHash());
                            contractDetails.put("requestSchema", contract.getRequestSchema());
                            contractDetails.put("decryptedResponseSchema", contract.getResponseSchema());
                            contractDetails.put("decryptedDataSchema", extractTemplateDataSchema(contract.getResponseSchema()));
                            contractDetails.put("responseStructure", Map.of(
                                    "envelope", "DOORS orchestration envelope",
                                    "instanceIdPath", "$[*].GePNIC_Instance_ID",
                                    "dataPath", "$[*].value.DATA",
                                    "description", "GePNIC_Instance_ID and type are outer response metadata; template fields are under value.DATA."
                            ));
                            export.put("contract", contractDetails);
                            export.put("transportEncryption", transportEncryptionContract());
                            return export;
                        }));
    }

    @Transactional(readOnly = true)
    public Map<String, Schema<?>> publishedResponseSchemas() {
        Map<String, Schema<?>> schemas = new LinkedHashMap<>();
        for (SqlTemplate template : approvedTemplates()) {
            contractRepository.findByTemplateQueryIdAndIsCurrentTrue(template.getQueryId())
                    .filter(contract -> "APPROVED".equals(contract.getContractStatus()))
                    .map(TemplateContract::getResponseSchema)
                    .filter(Objects::nonNull)
                    .ifPresent(schema -> schemas.put(
                            schemaName(template.getUniqueName()) + "Result",
                            objectMapper.convertValue(schema, Schema.class)));
        }
        return schemas;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> governanceCatalogue() {
        return approvedTemplates().stream().map(template -> {
            Map<String, Object> entry = baseEntry(template);
            Optional<TemplateContract> published = contractRepository
                    .findByTemplateQueryIdAndIsCurrentTrue(template.getQueryId());
            Optional<TemplateContract> candidate = contractRepository
                    .findTopByTemplateQueryIdAndContractStatusInOrderByContractVersionDesc(
                            template.getQueryId(), CANDIDATE);
            entry.put("contractStatus", candidate.map(TemplateContract::getContractStatus)
                    .orElseGet(() -> published.isPresent() ? "APPROVED" : "UNDISCOVERED"));
            published.ifPresent(value -> entry.put("published", toContractMap(value, false)));
            candidate.ifPresent(value -> entry.put("candidate", toContractMap(value, true)));
            return entry;
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(String uniqueName) {
        SqlTemplate template = requireTemplate(uniqueName);
        return contractRepository.findByTemplateQueryIdOrderByContractVersionDesc(template.getQueryId())
                .stream().map(value -> toContractMap(value, true)).toList();
    }

    /** Stores schema metadata only. No observed response values are persisted. */
    @Transactional
    public void captureObservation(String uniqueName, Object responsePayload) {
        if (!captureObservedResponses || uniqueName == null || responsePayload == null) return;
        approvedTemplate(uniqueName).ifPresent(template -> capture(template, responsePayload, "OBSERVED_EXECUTION"));
    }

    @Transactional
    public Map<String, Object> captureManualObservation(String uniqueName, Object responsePayload) {
        SqlTemplate template = requireApprovedTemplate(uniqueName);
        return toContractMap(capture(template, responsePayload, "MANUAL_DISCOVERY"), true);
    }

    @Transactional
    public Map<String, Object> updateDraft(String uniqueName, Map<String, Object> body, String actor) {
        SqlTemplate template = requireApprovedTemplate(uniqueName);
        TemplateContract contract = latestCandidate(template)
                .orElseThrow(() -> new IllegalStateException("No discovered contract is available for editing"));
        if (!EDITABLE.contains(contract.getContractStatus())) {
            throw new IllegalStateException("Only discovered, draft, or rejected contracts can be edited");
        }
        JsonNode request = body.containsKey("requestSchema")
                ? objectMapper.valueToTree(body.get("requestSchema")) : contract.getRequestSchema();
        JsonNode response = body.containsKey("responseSchema")
                ? objectMapper.valueToTree(body.get("responseSchema")) : contract.getResponseSchema();
        validateSchema(request, "requestSchema");
        validateSchema(response, "responseSchema");
        contract.setRequestSchema(canonicalize(request));
        contract.setResponseSchema(canonicalize(response));
        contract.setSchemaHash(hash(contract.getResponseSchema()));
        contract.setContractStatus("DRAFT");
        contract.setReviewComment(asText(body.get("reviewComment")));
        return toContractMap(contractRepository.save(contract), true);
    }

    @Transactional
    public Map<String, Object> submit(String uniqueName, String actor) {
        SqlTemplate template = requireApprovedTemplate(uniqueName);
        TemplateContract contract = latestCandidate(template)
                .orElseThrow(() -> new IllegalStateException("No candidate contract is available"));
        if (!List.of("DISCOVERED", "DRAFT", "REJECTED").contains(contract.getContractStatus())) {
            throw new IllegalStateException("Contract is not eligible for submission");
        }
        contract.setContractStatus("IN_REVIEW");
        contract.setSubmittedAt(LocalDateTime.now());
        contract.setSubmittedBy(actor);
        return toContractMap(contractRepository.save(contract), true);
    }

    @Transactional
    public Map<String, Object> approve(Long contractId, String actor, String comment) {
        TemplateContract contract = requireContract(contractId);
        if (!"IN_REVIEW".equals(contract.getContractStatus())) {
            throw new IllegalStateException("Only contracts in review can be approved");
        }
        contractRepository.findCurrentForUpdate(contract.getTemplate().getQueryId())
                .ifPresent(previous -> {
                    previous.setIsCurrent(false);
                    previous.setContractStatus("SUPERSEDED");
                    // The partial unique index permits only one current contract per
                    // template. Flush the demotion before promoting the candidate.
                    contractRepository.saveAndFlush(previous);
                });
        contract.setContractStatus("APPROVED");
        contract.setIsCurrent(true);
        contract.setReviewedAt(LocalDateTime.now());
        contract.setReviewedBy(actor);
        contract.setReviewComment(comment);
        contract.setPublishedAt(LocalDateTime.now());
        return toContractMap(contractRepository.save(contract), true);
    }

    @Transactional
    public Map<String, Object> reject(Long contractId, String actor, String comment) {
        TemplateContract contract = requireContract(contractId);
        if (!"IN_REVIEW".equals(contract.getContractStatus())) {
            throw new IllegalStateException("Only contracts in review can be rejected");
        }
        contract.setContractStatus("REJECTED");
        contract.setReviewedAt(LocalDateTime.now());
        contract.setReviewedBy(actor);
        contract.setReviewComment(comment);
        return toContractMap(contractRepository.save(contract), true);
    }

    @Transactional
    public Map<String, Object> restore(Long contractId, String actor) {
        TemplateContract source = requireContract(contractId);
        SqlTemplate template = source.getTemplate();
        TemplateContract restored = new TemplateContract();
        restored.setTemplate(template);
        restored.setContractVersion(nextVersion(template));
        restored.setRequestSchema(source.getRequestSchema().deepCopy());
        restored.setResponseSchema(source.getResponseSchema() == null ? null : source.getResponseSchema().deepCopy());
        restored.setSchemaHash(source.getSchemaHash());
        restored.setChangeType("RESTORE");
        restored.setDiscoverySource("RESTORED_VERSION");
        restored.setContractStatus("DRAFT");
        restored.setObservationCount(0L);
        restored.setReviewComment("Restored from contract version " + source.getContractVersion() + " by " + actor);
        restored.setIsCurrent(false);
        return toContractMap(contractRepository.save(restored), true);
    }

    public String schemaName(String uniqueName) {
        String cleaned = uniqueName == null ? "Template" : uniqueName.replaceAll("[^A-Za-z0-9]+", "_");
        if (cleaned.isBlank()) cleaned = "Template";
        if (Character.isDigit(cleaned.charAt(0))) cleaned = "T_" + cleaned;
        return cleaned;
    }

    private TemplateContract capture(SqlTemplate template, Object responsePayload, String source) {
        JsonNode responseSchema = canonicalize(inferSchema(objectMapper.valueToTree(responsePayload)));
        String schemaHash = hash(responseSchema);
        LocalDateTime now = LocalDateTime.now();

        Optional<TemplateContract> current = contractRepository
                .findByTemplateQueryIdAndIsCurrentTrue(template.getQueryId());
        if (current.filter(value -> schemaHash.equals(value.getSchemaHash())).isPresent()) {
            TemplateContract same = current.get();
            same.setObservationCount(same.getObservationCount() + 1);
            same.setLastObservedAt(now);
            return contractRepository.save(same);
        }

        Optional<TemplateContract> candidate = latestCandidate(template);
        if (candidate.filter(value -> schemaHash.equals(value.getSchemaHash())).isPresent()) {
            TemplateContract same = candidate.get();
            same.setObservationCount(same.getObservationCount() + 1);
            same.setLastObservedAt(now);
            return contractRepository.save(same);
        }

        TemplateContract contract = new TemplateContract();
        contract.setTemplate(template);
        contract.setContractVersion(nextVersion(template));
        contract.setRequestSchema(canonicalize(requestSchema(template)));
        contract.setResponseSchema(responseSchema);
        contract.setSchemaHash(schemaHash);
        contract.setChangeType(current.map(value -> classifyChange(value.getResponseSchema(), responseSchema))
                .orElse("NEW"));
        contract.setContractStatus("DISCOVERED");
        contract.setDiscoverySource(source);
        contract.setObservationCount(1L);
        contract.setFirstObservedAt(now);
        contract.setLastObservedAt(now);
        contract.setIsCurrent(false);
        return contractRepository.save(contract);
    }

    private Map<String, Object> toPublishedEntry(SqlTemplate template) {
        Map<String, Object> entry = baseEntry(template);
        Optional<TemplateContract> published = contractRepository
                .findByTemplateQueryIdAndIsCurrentTrue(template.getQueryId())
                .filter(value -> "APPROVED".equals(value.getContractStatus()));
        entry.put("contractStatus", published.isPresent() ? "APPROVED" : "UNDISCOVERED");
        JsonNode publishedRequest = published.map(TemplateContract::getRequestSchema)
                .orElseGet(() -> canonicalize(requestSchema(template)));
        entry.put("requestSchema", objectMapper.convertValue(publishedRequest, LinkedHashMap.class));
        entry.put("responseSchemaName", schemaName(template.getUniqueName()) + "Result");
        published.ifPresent(value -> {
            entry.put("contractVersion", value.getContractVersion());
            entry.put("responseSchema", objectMapper.convertValue(value.getResponseSchema(), LinkedHashMap.class));
            entry.put("publishedAt", value.getPublishedAt());
        });
        return entry;
    }

    private Map<String, Object> baseEntry(SqlTemplate template) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("templateId", template.getQueryId());
        entry.put("uniqueName", template.getUniqueName());
        entry.put("title", template.getUniqueName());
        entry.put("description", template.getDescription());
        entry.put("category", template.getCategory());
        entry.put("templateVersion", template.getVersion() == null ? 1 : template.getVersion());
        return entry;
    }

    private Map<String, Object> toContractMap(TemplateContract contract, boolean includeAudit) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("contractId", contract.getContractId());
        map.put("uniqueName", contract.getTemplate().getUniqueName());
        map.put("contractVersion", contract.getContractVersion());
        map.put("contractStatus", contract.getContractStatus());
        map.put("discoverySource", contract.getDiscoverySource());
        map.put("changeType", contract.getChangeType());
        map.put("schemaHash", contract.getSchemaHash());
        map.put("requestSchema", contract.getRequestSchema());
        map.put("responseSchema", contract.getResponseSchema());
        map.put("isCurrent", contract.getIsCurrent());
        map.put("observationCount", contract.getObservationCount());
        map.put("firstObservedAt", contract.getFirstObservedAt());
        map.put("lastObservedAt", contract.getLastObservedAt());
        map.put("publishedAt", contract.getPublishedAt());
        map.put("lockVersion", contract.getLockVersion());
        if (includeAudit) {
            map.put("submittedAt", contract.getSubmittedAt());
            map.put("submittedBy", contract.getSubmittedBy());
            map.put("reviewedAt", contract.getReviewedAt());
            map.put("reviewedBy", contract.getReviewedBy());
            map.put("reviewComment", contract.getReviewComment());
        }
        return map;
    }

    private JsonNode requestSchema(SqlTemplate template) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        ObjectNode agents = properties.putObject("agentIds");
        agents.put("type", "array");
        agents.putObject("items").put("type", "string");
        agents.put("minItems", 1);
        ObjectNode params = properties.putObject("params");
        params.put("type", "object");
        params.put("additionalProperties", false);
        ObjectNode parameterProperties = params.putObject("properties");
        ArrayNode requiredParams = params.putArray("required");
        for (String parameter : template.getParameters()) {
            if (parameter == null || parameter.isBlank()) continue;
            ObjectNode definition = parameterProperties.putObject(parameter);
            definition.put("type", "string");
            if (parameter.toLowerCase(Locale.ROOT).contains("date")) definition.put("format", "date");
            requiredParams.add(parameter);
        }
        root.putArray("required").add("agentIds").add("params");
        return root;
    }

    private Map<String, Object> transportEncryptionContract() {
        Map<String, Object> requestWrapper = new LinkedHashMap<>();
        requestWrapper.put("type", "object");
        requestWrapper.put("required", List.of("encryptedKey", "iv", "secureData", "signature"));
        requestWrapper.put("properties", Map.of(
                "encryptedKey", Map.of("type", "string", "format", "byte", "description", "AES request key encrypted with the DOORS RSA public key"),
                "iv", Map.of("type", "string", "format", "byte", "description", "Required random 12-byte AES-GCM nonce"),
                "secureData", Map.of("type", "string", "format", "byte", "description", "AES-256-GCM ciphertext with authentication tag"),
                "signature", Map.of("type", "string", "format", "byte", "description", "API Client RSA signature over the exact plaintext request JSON")
        ));

        Map<String, Object> responseWrapper = new LinkedHashMap<>();
        responseWrapper.put("type", "object");
        responseWrapper.put("required", List.of("encryptedKey", "iv", "secureData", "signature"));
        responseWrapper.put("properties", Map.of(
                "encryptedKey", Map.of("type", "string", "format", "byte", "description", "AES-256 response key encrypted with the API Client RSA public key"),
                "iv", Map.of("type", "string", "format", "byte", "description", "Random 16-byte AES-CBC initialization vector"),
                "secureData", Map.of("type", "string", "format", "byte", "description", "AES-CBC encrypted response JSON"),
                "signature", Map.of("type", "string", "format", "byte", "description", "DOORS SHA256withRSA signature over the exact decrypted response JSON")
        ));

        Map<String, Object> transport = new LinkedHashMap<>();
        transport.put("request", Map.of(
                "keyWrapAlgorithm", "RSA/ECB/PKCS1Padding",
                "payloadAlgorithm", "AES/GCM/NoPadding with a 128-bit authentication tag",
                "sessionKey", "Fresh AES-256 key per request",
                "signatureAlgorithms", List.of("SHA512withRSA", "SHA256withRSA configured fallback"),
                "wrapperSchema", requestWrapper
        ));
        transport.put("response", Map.of(
                "keyWrapAlgorithm", "RSA/ECB/PKCS1Padding",
                "payloadAlgorithm", "AES/CBC/PKCS5Padding",
                "sessionKey", "Fresh AES-256 key per response",
                "signatureAlgorithm", "SHA256withRSA",
                "wrapperSchema", responseWrapper
        ));
        transport.put("requirements", List.of(
                "HTTPS/TLS is mandatory",
                "Verify the signature over the exact decrypted UTF-8 JSON before parsing",
                "Do not log private keys, session keys, plaintext payloads, credentials, or signatures"
        ));
        return transport;
    }

    /**
     * Returns the template-owned DATA schema separately from the standard DOORS
     * orchestration envelope. The complete wire-format schema remains available
     * as decryptedResponseSchema for validation of the full decrypted response.
     */
    private JsonNode extractTemplateDataSchema(JsonNode responseSchema) {
        if (responseSchema == null) return null;
        JsonNode dataSchema = responseSchema.path("items")
                .path("properties")
                .path("value")
                .path("properties")
                .path("DATA");
        return dataSchema.isMissingNode() ? null : dataSchema.deepCopy();
    }

    private JsonNode inferSchema(JsonNode node) {
        ObjectNode schema = objectMapper.createObjectNode();
        if (node == null || node.isNull()) {
            schema.put("nullable", true);
            return schema;
        }
        if (node.isObject()) {
            schema.put("type", "object");
            schema.put("additionalProperties", false);
            ObjectNode properties = schema.putObject("properties");
            ArrayNode required = schema.putArray("required");
            node.fields().forEachRemaining(field -> {
                properties.set(field.getKey(), inferSchema(field.getValue()));
                if (!field.getValue().isNull()) required.add(field.getKey());
            });
            return schema;
        }
        if (node.isArray()) {
            schema.put("type", "array");
            if (node.isEmpty()) schema.set("items", objectMapper.createObjectNode());
            else {
                JsonNode itemSchema = inferSchema(node.get(0));
                for (int index = 1; index < node.size(); index++) {
                    itemSchema = mergeSchemas(itemSchema, inferSchema(node.get(index)));
                }
                schema.set("items", itemSchema);
            }
            return schema;
        }
        if (node.isBoolean()) schema.put("type", "boolean");
        else if (node.isIntegralNumber()) { schema.put("type", "integer"); schema.put("format", "int64"); }
        else if (node.isFloatingPointNumber()) { schema.put("type", "number"); schema.put("format", "double"); }
        else {
            schema.put("type", "string");
            String value = node.asText();
            try { LocalDate.parse(value); schema.put("format", "date"); }
            catch (Exception ignored) {
                try { OffsetDateTime.parse(value); schema.put("format", "date-time"); }
                catch (Exception ignoredAgain) {
                    try { UUID.fromString(value); schema.put("format", "uuid"); }
                    catch (Exception ignoredUuid) { }
                }
            }
        }
        return schema;
    }

    private JsonNode mergeSchemas(JsonNode left, JsonNode right) {
        if (left.equals(right)) return left;
        String leftType = left.path("type").asText("");
        String rightType = right.path("type").asText("");
        if (leftType.isBlank()) { ObjectNode result = right.deepCopy(); result.put("nullable", true); return result; }
        if (rightType.isBlank()) { ObjectNode result = left.deepCopy(); result.put("nullable", true); return result; }
        if (!leftType.equals(rightType)) {
            if (Set.of(leftType, rightType).equals(Set.of("integer", "number"))) {
                ObjectNode number = objectMapper.createObjectNode(); number.put("type", "number"); number.put("format", "double"); return number;
            }
            ObjectNode mixed = objectMapper.createObjectNode();
            ArrayNode oneOf = mixed.putArray("oneOf"); oneOf.add(left); oneOf.add(right); return mixed;
        }
        if ("array".equals(leftType)) {
            ObjectNode result = (ObjectNode) left.deepCopy();
            result.set("items", mergeSchemas(left.path("items"), right.path("items")));
            return result;
        }
        if (!"object".equals(leftType)) {
            ObjectNode result = (ObjectNode) left.deepCopy();
            if (!Objects.equals(left.path("format").asText(null), right.path("format").asText(null))) result.remove("format");
            return result;
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("type", "object"); result.put("additionalProperties", false);
        ObjectNode properties = result.putObject("properties");
        Set<String> names = new TreeSet<>();
        left.path("properties").fieldNames().forEachRemaining(names::add);
        right.path("properties").fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            JsonNode l = left.path("properties").get(name), r = right.path("properties").get(name);
            properties.set(name, l == null ? r : r == null ? l : mergeSchemas(l, r));
        }
        Set<String> requiredLeft = stringSet(left.path("required"));
        Set<String> requiredRight = stringSet(right.path("required"));
        requiredLeft.retainAll(requiredRight);
        ArrayNode required = result.putArray("required"); requiredLeft.stream().sorted().forEach(required::add);
        return result;
    }

    private String classifyChange(JsonNode published, JsonNode discovered) {
        if (published == null) return "NEW";
        if (canonicalize(published).equals(canonicalize(discovered))) return "NONE";
        return hasBreakingChange(published, discovered) ? "BREAKING" : "ADDITIVE";
    }

    private boolean hasBreakingChange(JsonNode oldSchema, JsonNode newSchema) {
        String oldType = oldSchema.path("type").asText("");
        String newType = newSchema.path("type").asText("");
        if (!oldType.equals(newType) && !("integer".equals(oldType) && "number".equals(newType))) return true;
        if ("array".equals(oldType)) return hasBreakingChange(oldSchema.path("items"), newSchema.path("items"));
        if (!"object".equals(oldType)) return false;
        JsonNode oldProps = oldSchema.path("properties"), newProps = newSchema.path("properties");
        Iterator<String> names = oldProps.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!newProps.has(name) || hasBreakingChange(oldProps.get(name), newProps.get(name))) return true;
        }
        Set<String> oldRequired = stringSet(oldSchema.path("required"));
        Set<String> newRequired = stringSet(newSchema.path("required"));
        newRequired.removeAll(oldRequired);
        return !newRequired.isEmpty();
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node == null) return null;
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            List<JsonNode> values = new ArrayList<>(); node.forEach(value -> values.add(canonicalize(value)));
            values.forEach(array::add); return array;
        }
        if (!node.isObject()) return node.deepCopy();
        ObjectNode object = objectMapper.createObjectNode();
        List<String> names = new ArrayList<>(); node.fieldNames().forEachRemaining(names::add); Collections.sort(names);
        for (String name : names) object.set(name, canonicalize(node.get(name)));
        return object;
    }

    private String hash(JsonNode schema) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsString(canonicalize(schema)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash the contract schema", exception);
        }
    }

    private void validateSchema(JsonNode schema, String field) {
        if (schema == null || !schema.isObject()) throw new IllegalArgumentException(field + " must be a JSON object");
        if (!schema.has("type") && !schema.has("oneOf")) throw new IllegalArgumentException(field + " must define type or oneOf");
    }

    private Set<String> stringSet(JsonNode array) {
        Set<String> values = new HashSet<>();
        if (array != null && array.isArray()) array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private List<SqlTemplate> approvedTemplates() {
        return templateRepository.findByStatusAndIsActive("APPROVED", true);
    }

    private Optional<SqlTemplate> approvedTemplate(String uniqueName) {
        return templateRepository.findByUniqueName(uniqueName)
                .filter(template -> "APPROVED".equalsIgnoreCase(template.getStatus()))
                .filter(template -> Boolean.TRUE.equals(template.getIsActive()));
    }

    private SqlTemplate requireTemplate(String uniqueName) {
        return templateRepository.findByUniqueName(uniqueName)
                .orElseThrow(() -> new NoSuchElementException("Unknown template: " + uniqueName));
    }

    private SqlTemplate requireApprovedTemplate(String uniqueName) {
        return approvedTemplate(uniqueName)
                .orElseThrow(() -> new NoSuchElementException("No active approved template: " + uniqueName));
    }

    private Optional<TemplateContract> latestCandidate(SqlTemplate template) {
        return contractRepository.findTopByTemplateQueryIdAndContractStatusInOrderByContractVersionDesc(
                template.getQueryId(), CANDIDATE);
    }

    private int nextVersion(SqlTemplate template) {
        return contractRepository.findTopByTemplateQueryIdOrderByContractVersionDesc(template.getQueryId())
                .map(value -> value.getContractVersion() + 1).orElse(1);
    }

    private TemplateContract requireContract(Long contractId) {
        return contractRepository.findById(contractId)
                .orElseThrow(() -> new NoSuchElementException("Unknown contract: " + contractId));
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
