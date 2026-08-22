package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.model.SqlTemplate;
import org.gepnic.doors.masterapi.repository.ApiClientRepository;
import org.gepnic.doors.masterapi.repository.ClientQueryMapRepository;
import org.gepnic.doors.masterapi.repository.SqlTemplateRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class SwaggerSessionService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ApiClientRepository apiClientRepository;
    private final ClientQueryMapRepository mappingRepository;
    private final SqlTemplateRepository templateRepository;
    private final JdbcTemplate jdbcTemplate;

    private final Map<String, LaunchGrant> launchGrants = new ConcurrentHashMap<>();
    private final Map<String, SwaggerSession> swaggerSessions = new ConcurrentHashMap<>();

    @Value("${doors.swagger.launch-ttl-seconds:60}")
    private long launchTtlSeconds;

    @Value("${doors.swagger.session-ttl-seconds:900}")
    private long sessionTtlSeconds;

    public Map<String, Object> createLaunch(Long clientId, String uniqueName, Map<String, Object> requestBody,
                                             String keyFingerprint, String actor, String userAgent) {
        cleanupExpired();

        ApiClient client = apiClientRepository.findById(clientId)
                .filter(value -> Boolean.TRUE.equals(value.getIsActive()))
                .orElseThrow(() -> new NoSuchElementException("Active API client not found"));
        if (!client.isEncryptionEnabled()) {
            throw new SecurityException(
                    "Swagger testing requires response encryption to be enabled for the selected API client");
        }
        SqlTemplate template = templateRepository.findByUniqueName(uniqueName)
                .filter(value -> "APPROVED".equalsIgnoreCase(value.getStatus()))
                .filter(value -> Boolean.TRUE.equals(value.getIsActive()))
                .orElseThrow(() -> new NoSuchElementException("Active approved template not found"));

        if (!mappingRepository.existsByClientIdAndQueryId(clientId, template.getQueryId())) {
            throw new SecurityException("The API client is not authorized for the selected template");
        }
        validateAgents(client, requestBody);

        Instant expiresAt = Instant.now().plusSeconds(launchTtlSeconds);
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("apiKey", client.getApiKey());
        configuration.put("uniqueName", template.getUniqueName());
        configuration.put("body", requestBody == null ? Map.of() : requestBody);
        configuration.put("keyFingerprint", keyFingerprint == null ? "" : keyFingerprint.trim());
        configuration.put("keySource", client.getClientPublicKey() == null ? "RUNTIME_REGISTRY" : "DATABASE");

        String rawToken = randomToken();
        launchGrants.put(hash(rawToken), new LaunchGrant(expiresAt, actor, userAgentHash(userAgent), configuration));
        return Map.of(
                "launchToken", rawToken,
                "expiresAt", expiresAt.toString(),
                "expiresInSeconds", launchTtlSeconds
        );
    }

    public Map<String, Object> exchange(String launchToken, String userAgent) {
        cleanupExpired();
        if (launchToken == null || launchToken.isBlank()) {
            throw new SecurityException("Swagger launch token is required");
        }

        LaunchGrant grant = launchGrants.remove(hash(launchToken));
        if (grant == null || grant.expiresAt().isBefore(Instant.now())) {
            throw new SecurityException("Swagger launch token is invalid, expired, or already used");
        }
        if (!MessageDigest.isEqual(
                grant.userAgentHash().getBytes(StandardCharsets.UTF_8),
                userAgentHash(userAgent).getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("Swagger launch token browser binding failed");
        }

        String rawSessionToken = randomToken();
        Instant expiresAt = Instant.now().plusSeconds(sessionTtlSeconds);
        swaggerSessions.put(hash(rawSessionToken), new SwaggerSession(
                expiresAt, grant.actor(), String.valueOf(grant.configuration().get("uniqueName"))));

        return Map.of(
                "swaggerSessionToken", rawSessionToken,
                "expiresAt", expiresAt.toString(),
                "configuration", grant.configuration()
        );
    }

    public boolean isActiveSession(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) return false;
        SwaggerSession session = swaggerSessions.get(hash(sessionToken));
        if (session == null) return false;
        if (session.expiresAt().isBefore(Instant.now())) {
            swaggerSessions.remove(hash(sessionToken));
            return false;
        }
        return true;
    }

    public String selectedUniqueName(String sessionToken) {
        if (!isActiveSession(sessionToken)) {
            throw new SecurityException("Swagger session is invalid or expired");
        }
        return swaggerSessions.get(hash(sessionToken)).uniqueName();
    }

    private void validateAgents(ApiClient client, Map<String, Object> requestBody) {
        if (requestBody == null || !(requestBody.get("agentIds") instanceof List<?> requestedAgents)) {
            throw new IllegalArgumentException("The Swagger request must contain agentIds");
        }
        List<String> allowedAgents = jdbcTemplate.queryForList(
                "SELECT agent_id FROM user_authorized_agents WHERE user_name = ?",
                String.class,
                client.getClientName()
        );
        boolean valid = !requestedAgents.isEmpty()
                && requestedAgents.stream().allMatch(value -> allowedAgents.contains(String.valueOf(value)));
        if (!valid) throw new SecurityException("The API client is not authorized for one or more selected agents");
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        launchGrants.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        swaggerSessions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to protect Swagger session token", exception);
        }
    }

    private String userAgentHash(String userAgent) {
        return hash(userAgent == null ? "" : userAgent);
    }

    private record LaunchGrant(Instant expiresAt, String actor, String userAgentHash,
                               Map<String, Object> configuration) { }

    private record SwaggerSession(Instant expiresAt, String actor, String uniqueName) { }
}
