package org.gepnic.doors.masterapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.config.AiraProperties;
import org.gepnic.doors.masterapi.model.UnifiedAuditLog;
import org.gepnic.doors.masterapi.repository.UnifiedAuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class AiraService {
    private static final int MAX_KNOWLEDGE_CONTEXT_CHARS = 3_500;
    private final AiraProperties properties;
    private final AiraContextService contextService;
    private final AiraKnowledgeService knowledgeService;
    private final UnifiedAuditLogRepository auditRepository;
    private final ObjectMapper objectMapper;
    private final AiraAgent agent;
    private final AiraOperationalAnswerService operationalAnswerService;

    @Autowired
    public AiraService(AiraProperties properties, AiraContextService contextService,
                       AiraKnowledgeService knowledgeService,
                       UnifiedAuditLogRepository auditRepository, ObjectMapper objectMapper,
                       AiraOperationalAnswerService operationalAnswerService) {
        this.properties = properties;
        this.contextService = contextService;
        this.knowledgeService = knowledgeService;
        this.auditRepository = auditRepository;
        this.objectMapper = objectMapper;
        this.operationalAnswerService = operationalAnswerService;
        this.agent = AiServices.builder(AiraAgent.class)
                .chatLanguageModel(OllamaChatModel.builder()
                        .baseUrl(properties.getBaseUrl())
                        .modelName(properties.getModel())
                        .temperature(0.0)
                        .numPredict(384)
                        .numCtx(8192)
                        .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                        .maxRetries(0)
                        .logRequests(false)
                        .logResponses(false)
                        .build())
                .build();
    }

    AiraService(AiraProperties properties, AiraContextService contextService,
                AiraKnowledgeService knowledgeService, UnifiedAuditLogRepository auditRepository,
                ObjectMapper objectMapper) {
        this(properties, contextService, knowledgeService, auditRepository, objectMapper,
                new AiraOperationalAnswerService(contextService));
    }

    public Map<String, Object> chat(String username, String prompt, String traceId, String clientIp) {
        return chat(username, Set.of("DATAMANAGER"), prompt, traceId, clientIp, false);
    }

    public Map<String, Object> chat(String username, String prompt, String traceId, String clientIp,
                                    boolean refineWithAi) {
        return chat(username, Set.of("DATAMANAGER"), prompt, traceId, clientIp, refineWithAi);
    }

    public Map<String, Object> chat(String username, Set<String> authorities, String prompt, String traceId,
                                    String clientIp, boolean refineWithAi) {
        long started = System.nanoTime();
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("Prompt cannot be empty");
        String sanitizedPrompt = prompt.trim();
        if (sanitizedPrompt.length() > properties.getMaxPromptChars()) {
            throw new IllegalArgumentException("Prompt exceeds the allowed length");
        }

        try {
            AiraOperationalIntent intent = AiraOperationalIntent.classify(sanitizedPrompt);
            if (intent.source() == AiraOperationalIntent.Source.OPERATIONAL) {
                Map<String, Object> result = operationalAnswerService.answer(intent, authorities);
                result.put("model", properties.getModel());
                audit(username, traceId, clientIp, 200, elapsed(started), null,
                        ((List<?>) result.get("metrics")).stream().filter(Map.class::isInstance)
                                .map(Map.class::cast).map(metric -> String.valueOf(metric.get("id")))
                                .collect(java.util.stream.Collectors.joining(",")));
                return result;
            }
            if (!properties.isEnabled()) throw new IllegalStateException("Aira is not enabled");
            Map<String, Object> liveResult = null;
            List<String> retrievedSnippets = isPasswordPolicyQuestion(sanitizedPrompt)
                    ? knowledgeService.findRelevantKnowledge(passwordPolicyRetrievalQuery(sanitizedPrompt), 12,
                            Math.max(0.32, properties.getRagMinScore() - 0.12))
                    : isSecurityOverview(sanitizedPrompt)
                            ? knowledgeService.findRelevantKnowledge(securityRetrievalQuery(sanitizedPrompt), 12,
                                    Math.max(0.35, properties.getRagMinScore() - 0.08))
                            : knowledgeService.findRelevantKnowledge(sanitizedPrompt);
            List<String> relevantSnippets = boundKnowledgeContext(
                    prioritizeKnowledge(sanitizedPrompt, removeSensitiveKnowledge(retrievedSnippets)));
            StringBuilder refData = new StringBuilder("DOORS REFERENCE TEXT BEGIN\n");
            if (!relevantSnippets.isEmpty()) {
                for (int i = 0; i < relevantSnippets.size(); i++) {
                    refData.append("\n[Retrieved chunk ").append(i + 1).append("]\n")
                            .append(relevantSnippets.get(i)).append('\n');
                }
            }
            if (intent.source() == AiraOperationalIntent.Source.MIXED) {
                liveResult = operationalAnswerService.answer(intent, authorities);
                refData.append("\n[LIVE SYSTEM SNAPSHOT]\n").append(liveResult.get("answer")).append('\n');
            }
            refData.append("\nDOORS REFERENCE TEXT END\n\n");

            String answer;
            boolean refinedByAi = false;
            boolean automaticDocumentRefinement = intent.source() == AiraOperationalIntent.Source.DOCUMENTATION
                    && !relevantSnippets.isEmpty() && !refineWithAi;
            try {
                String composedPrompt = generationControlPrefix() + refData + "QUESTION: " + escapePromptMarkup(sanitizedPrompt)
                        + "\nAnswer directly and concisely from the reference text only. Lead with the items specifically requested; omit generic preamble."
                        + responseFocus(sanitizedPrompt);
                answer = agent.chat(composedPrompt);
                if (isIncompleteAnswer(answer)) {
                    log.warn("AIra returned an incomplete response; retrying once [model: {}]", properties.getModel());
                    answer = agent.chat(generationControlPrefix() + refData + "QUESTION: " + escapePromptMarkup(sanitizedPrompt)
                            + "\nThe previous response was incomplete. Give the complete answer directly from the reference text only.");
                }
                if (isIncompleteAnswer(answer)) {
                    throw new IllegalStateException("Aira generated an incomplete or ungrounded response; please retry");
                }
                if (!relevantSnippets.isEmpty()
                        && !answer.toLowerCase(java.util.Locale.ROOT).contains("sources:")) {
                    answer = answer.trim() + "\n\n" + AiraExtractiveFormatter.formatSources(relevantSnippets);
                }
                refinedByAi = true;
            } catch (RuntimeException modelFailure) {
                if (!automaticDocumentRefinement) throw modelFailure;
                log.warn("Automatic document refinement failed; returning governed extractive answer [model: {}, error: {}]",
                        properties.getModel(), modelFailure.getMessage());
                answer = buildExtractiveKnowledgeAnswer(relevantSnippets);
            }
            audit(username, traceId, clientIp, 200, elapsed(started), null);

            Map<String, Object> result = new java.util.HashMap<>();
            result.put("answer", answer);
            result.put("executionDisabled", true);
            result.put("model", properties.getModel());
            result.put("knowledgeSnippetsMatched", relevantSnippets.size());
            result.put("grounded", !relevantSnippets.isEmpty());
            result.put("refined", refinedByAi);
            result.put("answerType", intent.source() == AiraOperationalIntent.Source.MIXED
                    ? "MIXED" : "DOCUMENT_GROUNDED");
            if (liveResult != null) {
                result.put("metrics", liveResult.get("metrics"));
                result.put("executionData", liveResult.get("executionData"));
                result.put("capturedAt", liveResult.get("capturedAt"));
            }
            return result;
        } catch (SecurityException ex) {
            audit(username, traceId, clientIp, 403, elapsed(started), "METRIC_ACCESS_DENIED", null);
            throw ex;
        } catch (RuntimeException ex) {
            log.error("AIra chat failed [model: {}, elapsedMs: {}, error: {}]",
                    properties.getModel(), elapsed(started), ex.getMessage(), ex);
            audit(username, traceId, clientIp, 503, elapsed(started), ex.getClass().getSimpleName(), null);
            throw ex;
        }
    }

    public int reindexKnowledge() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Aira is not enabled");
        }
        return knowledgeService.reindexKnowledge();
    }

    public Map<String, Object> status() {
        Map<String, Object> statusMap = new java.util.HashMap<>();
        statusMap.put("enabled", properties.isEnabled());
        statusMap.put("model", properties.getModel());
        statusMap.put("embeddingModel", properties.getEmbeddingModel());
        statusMap.put("knowledgeBaseReady", knowledgeService.isReady());
        statusMap.put("knowledgeSegmentsCount", knowledgeService.getSegmentCount());
        statusMap.put("operationalAvailable", true);

        if (!properties.isEnabled()) {
            statusMap.put("online", false);
            statusMap.put("modelAvailable", false);
            return statusMap;
        }
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(trimSlash(properties.getBaseUrl()) + "/api/tags"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            boolean modelAvailable = false;
            if (response.statusCode() == 200) {
                JsonNode models = objectMapper.readTree(response.body()).path("models");
                for (JsonNode model : models) {
                    String name = model.path("name").asText();
                    if (name.equals(properties.getModel()) || name.startsWith(properties.getModel() + ":")) {
                        modelAvailable = true;
                        break;
                    }
                }
            }
            statusMap.put("online", response.statusCode() == 200 && modelAvailable);
            statusMap.put("modelAvailable", modelAvailable);
            return statusMap;
        } catch (Exception ex) {
            log.debug("Aira health check failed: {}", ex.getMessage());
            statusMap.put("online", false);
            statusMap.put("modelAvailable", false);
            return statusMap;
        }
    }

    private void audit(String username, String traceId, String clientIp, int status, long duration, String error) {
        audit(username, traceId, clientIp, status, duration, error, null);
    }

    private void audit(String username, String traceId, String clientIp, int status, long duration, String error,
                       String requestedMetrics) {
        if (auditRepository == null) return;
        try {
            auditRepository.save(UnifiedAuditLog.builder().eventType("AIRA_CHAT").username(username)
                    .endpoint("/api/v1/master/aira/chat").method("POST").statusCode(status)
                    .durationMs(duration).clientIp(clientIp).traceId(traceId).errorCode(error)
                    .queryName(requestedMetrics).build());
        } catch (RuntimeException auditFailure) {
            log.error("Unable to persist Aira audit event", auditFailure);
        }
    }

    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
    private static String trimSlash(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
    private String generationControlPrefix() {
        return properties.getModel().toLowerCase(java.util.Locale.ROOT).startsWith("qwen3") ? "/no_think\n" : "";
    }
    private static String responseFocus(String prompt) {
        String normalized = prompt.toLowerCase(Locale.ROOT);
        if (isPasswordPolicyQuestion(prompt)) {
            return "\nState the currently enforced password rules explicitly: minimum and maximum length, required character classes, whitespace rule, and replacement-password rule. Then briefly state password storage and forced-change behavior. Do not quote or reveal default, temporary, historical, example, or real passwords.";
        }
        if (isSecurityOverview(prompt)) {
            return """
                    \nThis is a DOORS security-overview question. Organize the answer by implemented controls, not by document titles or user manuals.
                    Cover only controls supported by the retrieved text, prioritizing: authentication and role-based access; segregation of duties; three-plane isolation; transport and payload cryptography; API-client identity and request integrity; parameterized governed execution and data minimization; protected document delivery; auditability; and fail-closed enforcement.
                    Do not present manuals, FAQs, controlled-copy labels, or troubleshooting instructions as security features.
                    Use concise bullets and cite only the security sources actually supplied in the reference text.
                    """;
        }
        if (normalized.contains("rbac") || normalized.contains("role-based access")) {
            return "\nFor RBAC questions, enumerate Security Administrator, Data Manager, Developer, Data Viewer, External User and API User with the permitted purpose or boundary stated in the reference, then explain segregation rules.";
        }
        return "";
    }
    private static String escapePromptMarkup(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static boolean isIncompleteAnswer(String answer) {
        if (answer == null) return true;
        String normalized = answer.trim().replaceAll("\\s+", " ");
        if (normalized.length() < 40) return true;
        String lower = normalized.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("note:") || lower.equals("answer:") || lower.equals("response:");
    }

    static boolean isUnacceptableAnswer(String answer, boolean sourceCitationRequired) {
        return isIncompleteAnswer(answer) || (sourceCitationRequired
                && !answer.toLowerCase(java.util.Locale.ROOT).contains("sources:"));
    }

    static boolean requiresLiveSnapshot(String prompt) {
        return AiraOperationalIntent.classify(prompt).source() != AiraOperationalIntent.Source.DOCUMENTATION;
    }

    static List<String> boundKnowledgeContext(List<String> snippets) {
        List<String> bounded = new java.util.ArrayList<>();
        int remaining = MAX_KNOWLEDGE_CONTEXT_CHARS;
        for (String snippet : snippets) {
            if (snippet == null || snippet.isBlank() || remaining <= 0) continue;
            String value = snippet.trim();
            if (value.length() > remaining) value = value.substring(0, remaining);
            bounded.add(value);
            remaining -= value.length();
        }
        return bounded;
    }

    static String buildExtractiveKnowledgeAnswer(List<String> snippets) {
        return AiraExtractiveFormatter.format(snippets);
    }

    static boolean isSecurityOverview(String prompt) {
        String value = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        boolean security = value.contains("security") || value.contains("secure") || value.contains("protect");
        boolean overview = value.contains("feature") || value.contains("control") || value.contains("overview")
                || value.contains("explain") || value.contains("how does") || value.contains("how is");
        return security && overview;
    }

    static String securityRetrievalQuery(String prompt) {
        return prompt + " DOORS security architecture role based access control RBAC segregation of duties "
                + "three plane isolation authentication MFA TLS RSA AES digital signatures API client certificates "
                + "parameterized approved queries data minimization document delivery checksum audit logging fail closed";
    }

    static boolean isPasswordPolicyQuestion(String prompt) {
        String value = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        return value.contains("password") && (value.contains("policy") || value.contains("rule")
                || value.contains("requirement") || value.contains("length") || value.contains("complexity")
                || value.contains("change password") || value.contains("reset password"));
    }

    static String passwordPolicyRetrievalQuery(String prompt) {
        return prompt + " current enforced DOORS password composition minimum 12 maximum 128 uppercase lowercase "
                + "number special character no whitespace replacement differs BCrypt SHA-256 forced change MFA TOTP";
    }

    static List<String> prioritizeKnowledge(String prompt, List<String> snippets) {
        if (isPasswordPolicyQuestion(prompt)) {
            if (snippets == null || snippets.isEmpty()) return snippets;
            return snippets.stream()
                    .filter(AiraService::isCanonicalPasswordSnippet)
                    .sorted((left, right) -> Integer.compare(passwordSnippetScore(right), passwordSnippetScore(left)))
                    .limit(6)
                    .toList();
        }
        if (!isSecurityOverview(prompt) || snippets == null || snippets.isEmpty()) return snippets;
        return snippets.stream()
                .sorted((left, right) -> Integer.compare(securitySnippetScore(right), securitySnippetScore(left)))
                .filter(snippet -> securitySnippetScore(snippet) > -1_500)
                .limit(8)
                .toList();
    }

    static List<String> removeSensitiveKnowledge(List<String> snippets) {
        if (snippets == null || snippets.isEmpty()) return snippets;
        return snippets.stream().filter(snippet -> {
            String value = snippet == null ? "" : snippet.toLowerCase(Locale.ROOT);
            return !value.contains("default password") && !value.contains("doors@nic")
                    && !value.contains("doors\\@nic");
        }).toList();
    }

    private static boolean isCanonicalPasswordSnippet(String snippet) {
        String value = snippet == null ? "" : snippet.toLowerCase(Locale.ROOT);
        return value.contains("password-policy-mfa.pdf")
                || value.contains("password policy and multi-factor authentication");
    }

    private static int passwordSnippetScore(String snippet) {
        String value = snippet == null ? "" : snippet.toLowerCase(Locale.ROOT);
        int score = value.contains("password-policy-mfa.pdf") ? 5_000 : 3_000;
        if (value.contains("password composition") || value.contains("12 characters")) score += 2_000;
        if (value.contains("password storage") || value.contains("bcrypt")) score += 1_000;
        if (value.contains("temporary password") || value.contains("forced change")) score += 500;
        return score;
    }

    private static int securitySnippetScore(String snippet) {
        String value = snippet == null ? "" : snippet.toLowerCase(Locale.ROOT);
        int score = 0;
        if (value.contains("security-architecture-controls.pdf")) score += 5_000;
        if (value.contains("rbac-segregation-duties.pdf")) score += 4_500;
        if (value.contains("end-to-end-encryption-process.pdf")) score += 4_000;
        if (value.contains("ui-backend-encryption-process.pdf")) score += 3_800;
        if (value.contains("hosting-deployment-operations.pdf")) score += 1_500;
        if (value.contains("role-based access") || value.contains("segregation of duties")) score += 900;
        if (value.contains("three plane") || value.contains("plane isolation")) score += 850;
        if (value.contains("tls") || value.contains("digital signature") || value.contains("checksum")) score += 700;
        if (value.contains("audit") || value.contains("fail closed")) score += 500;
        if (value.contains("manual.pdf") || value.contains("faq-glossary-support.pdf")
                || value.contains("troubleshooting-runbooks.pdf") || value.contains("platform-reference.pdf")) score -= 2_000;
        if (value.contains("controlled copy")) score -= 700;
        return score;
    }
}
