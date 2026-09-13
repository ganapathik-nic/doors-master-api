package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.model.Agent;
import org.gepnic.doors.masterapi.model.SqlTemplate;
import org.gepnic.doors.masterapi.model.User;
import org.gepnic.doors.masterapi.repository.AgentRepository;
import org.gepnic.doors.masterapi.repository.UserRepository;
import org.gepnic.doors.masterapi.util.EncryptionUtils;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.JdbcTemplate; 
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate; 
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutionService {

    private final AgentRepository agentRepository;
    private final UserRepository userRepository;
    private final WebClient.Builder webClientBuilder;
    private final JdbcTemplate jdbcTemplate; 
    private final RestTemplate restTemplate; 
    @org.springframework.beans.factory.annotation.Autowired
    private AgentTransitEncryption transitEncryption;

    private static final Pattern SQL_INJECTION_PATTERN = Pattern.compile(
        "(?i)(--|;|\\bUNION\\b|\\bSELECT\\b|\\bDROP\\b|\\bOR\\b\\s+\\d+=\\d+|\\bUPDATE\\b|\\bDELETE\\b)", 
        Pattern.CASE_INSENSITIVE
    );

    public List<String> getAuthorizedExecutionTargets(SqlTemplate template) {
        String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();
        
        // Securely fetch authorized agents for the user
        String authSql = "SELECT agent_id FROM user_authorized_agents WHERE user_name = ?";
        List<String> userAuthorizedAgents = jdbcTemplate.queryForList(authSql, String.class, currentUsername);

        List<String> queryAllowedAgents = template.getAuthorizedAgents();

        return queryAllowedAgents.stream()
                .filter(userAuthorizedAgents::contains)
                .collect(Collectors.toList());
    }

    public Map<String, Object> executeDryRun(String agentId, String base64Payload, Map<String, Object> params) {
        // 1. Fetch target agent configuration row from your JPA repository registry
        Agent agent = agentRepository.findById(agentId.trim())
                .orElseThrow(() -> new NoSuchElementException("Agent record missing for ID: " + agentId));

        // 2. Normalize base url strings
        String rawBaseUrl = agent.getBaseUrl() != null ? agent.getBaseUrl().trim() : "";
        while (rawBaseUrl.endsWith("/")) {
            rawBaseUrl = rawBaseUrl.substring(0, rawBaseUrl.length() - 1);
        }

        // 3. 🚀 THE ARCHITECTURE REFACTOR ROUTER
        int schemeSeparator = rawBaseUrl.indexOf("://");
        int authorityStart = schemeSeparator + 3;
        if (schemeSeparator <= 0 ||
                authorityStart >= rawBaseUrl.length() ||
                rawBaseUrl.indexOf('/', authorityStart) == authorityStart) {
            throw new IllegalArgumentException("Malformed base_url in registry for agent: " + agentId);
        }

        String extractedInstanceCode =
                agent.getAgentInstanceCode() != null &&
                !agent.getAgentInstanceCode().isBlank()
                        ? agent.getAgentInstanceCode().trim()
                        : agentId.trim();
        String proxyNetworkRoot = rawBaseUrl;

        String configuredSuffix = "/" + extractedInstanceCode;
        boolean hasConfiguredSuffix =
                rawBaseUrl.length() >= configuredSuffix.length() &&
                rawBaseUrl.regionMatches(
                        true,
                        rawBaseUrl.length() - configuredSuffix.length(),
                        configuredSuffix,
                        0,
                        configuredSuffix.length()
                );
        if (hasConfiguredSuffix) {
            proxyNetworkRoot = rawBaseUrl.substring(
                    0,
                    rawBaseUrl.length() - configuredSuffix.length()
            );
        } else {
            int legacyMarker = rawBaseUrl.toLowerCase(Locale.ROOT)
                    .lastIndexOf("/doorsagent/");
            if (legacyMarker >= authorityStart) {
                String legacyInstanceCode = rawBaseUrl.substring(
                        legacyMarker + "/doorsagent/".length()
                );
                if (!legacyInstanceCode.isBlank() &&
                        !legacyInstanceCode.contains("/")) {
                    extractedInstanceCode = legacyInstanceCode;
                    proxyNetworkRoot = rawBaseUrl.substring(
                            0,
                            rawBaseUrl.length() -
                                    legacyInstanceCode.length() - 1
                    );
                }
            }
        }

        // 🎯 THE CRITICAL ALIGNMENT FIX: Target the raw, unslashed root endpoint configuration
        String endpoint = proxyNetworkRoot + "/v1/agent/query/dry-run";

        log.info("DOORS-GOVERNANCE: Running dry-run validation routing against endpoint -> {}", endpoint);

        // 4. Build outbound payload envelope frame matching your Agent requirements
        Map<String, Object> agentPayload = new HashMap<>();
        
       // 🛡️ THE DECODING WORKFLOW ARCHITECTURE (HARDENED 🔐)
        // 🛡️ THE DECODING WORKFLOW ARCHITECTURE (CORRECTED SPACING 🔐)
        String decodedSql = "";
        if (base64Payload != null && !base64Payload.isBlank()) {
            
            // 1. Strip whitespaces ONLY for the raw ciphertext transport wrapper stage
            String sanitizedPayload = stripMatchingSqlQuotes(base64Payload.trim());
            if (!looksLikePlainSql(sanitizedPayload)) {
                sanitizedPayload = sanitizedPayload.replaceAll("\\s", "");
            }
            
            // 2. CRYPTOGRAPHIC EVALUATION GATEWAY
            if (!looksLikePlainSql(sanitizedPayload) && sanitizedPayload.length() > 30) {
                try {
                    log.info("DOORS-DRYRUN-SERVICE: Decrypting secured transit envelope...");
                    sanitizedPayload = EncryptionUtils.decrypt(sanitizedPayload);
                    // 🚀 DO NOT execute a global "\\s" replacement here! It strips out legitimate SQL spaces.
                    sanitizedPayload = stripMatchingSqlQuotes(sanitizedPayload.trim());
                } catch (Exception cryptoEx) {
                    log.debug("DOORS-DRYRUN-SERVICE: Cipher pass skipped. Parsing payload as raw string.");
                }
            }

            // 3. SECURE STRINGS BASE64 EXTRACTION
            try {
                // If it's a Base64 payload block string, remove its specific transit spacing parameters
                String base64TargetStr = sanitizedPayload.replaceAll("\\s", "");
                byte[] decodedBytes = java.util.Base64.getDecoder().decode(base64TargetStr);
                decodedSql = new String(decodedBytes, java.nio.charset.StandardCharsets.UTF_8);
                log.info("DOORS-DRYRUN-SERVICE: Successfully unpacked Base64 statement query data template.");
            } catch (IllegalArgumentException decodeEx) {
                log.warn("DOORS-DRYRUN-SERVICE: Payload string was plain text text layout. Using stream as matches.");
                // 🎯 Keep the spaces intact!
                decodedSql = sanitizedPayload; 
            }
        }
        if (!org.gepnic.doors.masterapi.util.SqlSecurityValidator.isSafeSelectOnly(decodedSql))
            throw new SecurityException("Only a single read-only SELECT is permitted");
        agentPayload.put("sql", decodedSql);
        agentPayload.put("params", params != null ? params : new HashMap<>());
        agentPayload.put("instanceCode", extractedInstanceCode);

        // Inject target database credentials dynamically from the matching repository record row
        agentPayload.put("dbHost", agent.getTargetDbHost() != null ? agent.getTargetDbHost().trim() : "");
        agentPayload.put("dbPort", agent.getTargetDbPort());
        agentPayload.put("dbName", agent.getTargetDbName() != null ? agent.getTargetDbName().trim() : "");
        agentPayload.put("dbUser", agent.getTargetDbUser() != null ? agent.getTargetDbUser().trim() : "");
        
        // 5. 🛡️ Dispatch payload inside the try-catch block to handle checked exceptions gracefully
        try {
            // 🚀 THE FIX: Checked exception source is now safely wrapped inside the error handler frame
            if (agent.getTargetDbPassword() != null && !agent.getTargetDbPassword().isBlank()) {
                String encryptedPass = transitEncryption.encryptPassword(agent.getTargetDbPassword().trim());
                agentPayload.put("dbPasswordSecure", encryptedPass);
            } else {
                agentPayload.put("dbPasswordSecure", "");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(endpoint, agentPayload, Map.class);
            if (response == null) {
                throw new IllegalStateException("Agent dry-run endpoint returned an empty response");
            }
            if (Boolean.FALSE.equals(response.get("success"))) {
                Object agentMessage = response.get("message");
                throw new IllegalStateException(
                        agentMessage != null && !agentMessage.toString().isBlank()
                                ? agentMessage.toString()
                                : "Agent rejected the dry-run request"
                );
            }
            return response;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("DOORS-GOVERNANCE: Proxy handoff failure or encryption fault on route [{}]: {}", endpoint, ex.getMessage());
            throw new IllegalStateException(
                    "Unable to execute dry-run through agent " + agentId +
                            ": " + ex.getMessage(),
                    ex
            );
        }
    }
    private boolean looksLikePlainSql(String value) {
        if (value == null) return false;
        String normalized = value.stripLeading().toUpperCase(Locale.ROOT);
        return normalized.startsWith("SELECT") || normalized.startsWith("WITH");
    }

    public Map<String, Object> evaluateDocumentEligibility(
            String agentId,
            org.gepnic.doors.masterapi.model.DocumentDownloadPolicy policy,
            Map<String, Object> params) {
        Agent agent = agentRepository.findById(agentId.trim())
                .filter(value -> Boolean.TRUE.equals(value.getIsActive()))
                .orElseThrow(() -> new NoSuchElementException("Active Agent not found: " + agentId));
        String endpoint = agentEndpoint(agent, "/v1/agent/query/document-eligibility");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executionMode", policy.getExecutionMode());
        payload.put("functionName", policy.getFunctionName());
        payload.put("eligibilitySql", policy.getEligibilitySql());
        List<String> functionArguments = new ArrayList<>();
        policy.getAcceptedIdentifiers().forEach(value -> functionArguments.add(value.asText()));
        payload.put("functionArguments", functionArguments);
        payload.put("decisionColumn", policy.getDecisionColumn());
        payload.put("allowedValue", policy.getAllowedValue());
        payload.put("params", params);
        payload.put("dbHost", agent.getTargetDbHost());
        payload.put("dbPort", agent.getTargetDbPort());
        payload.put("dbName", agent.getTargetDbName());
        payload.put("dbUser", agent.getTargetDbUser());
        try {
            payload.put("dbPasswordSecure", agent.getTargetDbPassword() == null ? "" :
                    transitEncryption.encryptPassword(agent.getTargetDbPassword().trim()));
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(endpoint, payload, Map.class);
            if (response == null) throw new IllegalStateException("Agent returned an empty eligibility response");
            return response;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to evaluate document eligibility through Agent " + agentId, exception);
        }
    }

    private String agentEndpoint(Agent agent, String path) {
        String base = agent.getBaseUrl() == null ? "" : agent.getBaseUrl().trim().replaceAll("/+$", "");
        if (!base.matches("https?://[^/]+.*")) throw new IllegalArgumentException("Malformed Agent base URL");
        String instanceCode = agent.getAgentInstanceCode() == null ? "" : agent.getAgentInstanceCode().trim();
        String routingSuffix = !instanceCode.isBlank() ? instanceCode : agent.getAgentId();
        if (routingSuffix != null && !routingSuffix.isBlank()
                && base.toLowerCase(Locale.ROOT).endsWith(("/" + routingSuffix).toLowerCase(Locale.ROOT))) {
            base = base.substring(0, base.length() - routingSuffix.length() - 1);
        }
        return base + path;
    }

    private String stripMatchingSqlQuotes(String value) {
        if (value == null || value.length() < 2) return value;
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            String candidate = value.substring(1, value.length() - 1).trim();
            if (looksLikePlainSql(candidate)) return candidate;
        }
        return value;
    }

    private void validateParams(Map<String, Object> params) {
        if (params == null) return;
        for (Object value : params.values()) {
            if (value instanceof String strValue) {
                if (SQL_INJECTION_PATTERN.matcher(strValue).find()) {
                    throw new SecurityException("Security Violation: Malicious patterns detected in input.");
                }
            }
        }
    }
}
