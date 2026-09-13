package org.gepnic.doors.masterapi.service;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

@Service
class AiraOperationalAnswerService {
    private static final Set<String> MANAGER_ROLES = Set.of("DATAMANAGER", "ADMIN", "SECURITYADMIN");
    private final AiraContextService contextService;

    AiraOperationalAnswerService(AiraContextService contextService) { this.contextService = contextService; }

    Map<String, Object> answer(AiraOperationalIntent intent, Set<String> authorities) {
        enforceAccess(intent.domain(), authorities);
        Map<String, Object> snapshot = contextService.buildOperationalSnapshot();
        if (intent.domain() == AiraOperationalIntent.Domain.INSIGHTS) return insights(snapshot);
        Map<String, Object> values = domain(snapshot, intent);
        List<Map<String, Object>> metrics = selectMetrics(values, intent);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", format(intent, metrics));
        result.put("answerType", "LIVE_OPERATIONAL");
        result.put("metrics", metrics);
        result.put("executionData", metrics);
        result.put("capturedAt", snapshot.get("capturedAt"));
        result.put("knowledgeSnippetsMatched", 0);
        result.put("grounded", true);
        result.put("refined", false);
        result.put("executionDisabled", true);
        return result;
    }

    private void enforceAccess(AiraOperationalIntent.Domain domain, Set<String> authorities) {
        Set<String> roles = authorities.stream().map(AiraOperationalAnswerService::normalizeRole).collect(Collectors.toSet());
        if ((domain == AiraOperationalIntent.Domain.USERS || domain == AiraOperationalIntent.Domain.API_CLIENTS
                || domain == AiraOperationalIntent.Domain.INSIGHTS || domain == AiraOperationalIntent.Domain.PLATFORM)
                && roles.stream().noneMatch(MANAGER_ROLES::contains)) {
            throw new SecurityException("Your role is not permitted to view this operational metric");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> domain(Map<String, Object> snapshot, AiraOperationalIntent intent) {
        String key = switch (intent.domain()) {
            case QUERIES -> "queries"; case AGENTS -> "agents"; case USERS -> "users";
            case DATA_REQUESTS -> "dataRequests"; case API_CLIENTS -> "apiClients";
            case EXECUTIONS -> "executions"; case DOCUMENT_DOWNLOADS -> "documentDownloads";
            case INSIGHTS, PLATFORM -> null;
        };
        if (key == null) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("pendingQueries", ((Map<String, Object>) snapshot.get("queries")).get("pending"));
            summary.put("activeAgents", ((Map<String, Object>) snapshot.get("agents")).get("active"));
            summary.put("pendingUsers", ((Map<String, Object>) snapshot.get("users")).get("pending"));
            summary.put("pendingDataRequests", ((Map<String, Object>) snapshot.get("dataRequests")).get("pending"));
            return summary;
        }
        Map<String, Object> values = (Map<String, Object>) snapshot.get(key);
        if (intent.domain() == AiraOperationalIntent.Domain.EXECUTIONS
                || intent.domain() == AiraOperationalIntent.Domain.DOCUMENT_DOWNLOADS) {
            String period = switch (intent.period()) {
                case TODAY -> "today"; case THIRTY_DAYS -> "thirtyDays"; default -> "sevenDays";
            };
            return (Map<String, Object>) values.get(period);
        }
        return values;
    }

    private List<Map<String, Object>> selectMetrics(Map<String, Object> values, AiraOperationalIntent intent) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (intent.metric() == AiraOperationalIntent.Metric.BREAKDOWN || intent.domain() == AiraOperationalIntent.Domain.PLATFORM) {
            values.forEach((key, value) -> result.add(metric(intent, key, value)));
        } else if (intent.metric() == AiraOperationalIntent.Metric.SUCCESS_RATE) {
            long total = number(values.get("total"));
            long successful = number(values.get("successful"));
            double rate = total == 0 ? 0D : Math.round(successful * 10_000D / total) / 100D;
            result.add(metric(intent, "successRatePercent", rate));
        } else {
            String key = metricKey(intent, values);
            result.add(metric(intent, key, values.getOrDefault(key, 0)));
        }
        return result;
    }

    private String metricKey(AiraOperationalIntent intent, Map<String, Object> values) {
        if (intent.status() == null) return "total";
        String requested = intent.status().toLowerCase(Locale.ROOT);
        if ("success".equals(requested)) requested = "successful";
        return requested;
    }

    private Map<String, Object> metric(AiraOperationalIntent intent, String key, Object value) {
        return Map.of("id", intent.domain().name().toLowerCase(Locale.ROOT) + "." + key,
                "label", label(key), "value", value);
    }

    private String format(AiraOperationalIntent intent, List<Map<String, Object>> metrics) {
        if (metrics.size() > 1) return "Current DOORS status: " + metrics.stream()
                .map(m -> m.get("label") + " = " + m.get("value")).collect(Collectors.joining(", ")) + ".";
        Map<String, Object> metric = metrics.get(0);
        String subject = switch (intent.domain()) {
            case QUERIES -> "queries"; case AGENTS -> "agents"; case USERS -> "users";
            case DATA_REQUESTS -> "data requests"; case API_CLIENTS -> "API clients";
            case EXECUTIONS -> "executions"; case DOCUMENT_DOWNLOADS -> "document downloads";
            case INSIGHTS -> "insights"; case PLATFORM -> "items";
        };
        String qualifier = "total".equals(metric.get("label")) ? "" : " " + metric.get("label");
        return "There are " + metric.get("value") + qualifier + " " + subject + " in the current DOORS system.";
    }

    private static long number(Object value) { return value instanceof Number n ? n.longValue() : 0; }

    @SuppressWarnings("unchecked")
    private Map<String, Object> insights(Map<String, Object> snapshot) {
        Map<String, Object> queries = (Map<String, Object>) snapshot.get("queries");
        Map<String, Object> agents = (Map<String, Object>) snapshot.get("agents");
        Map<String, Object> users = (Map<String, Object>) snapshot.get("users");
        Map<String, Object> requests = (Map<String, Object>) snapshot.get("dataRequests");
        Map<String, Object> executions = (Map<String, Object>) ((Map<String, Object>) snapshot.get("executions")).get("today");
        Map<String, Object> downloads = (Map<String, Object>) ((Map<String, Object>) snapshot.get("documentDownloads")).get("today");

        List<Map<String, Object>> metrics = List.of(
                insightMetric("governance.pendingQueries", "Pending queries", queries.get("pending"), "ATTENTION"),
                insightMetric("governance.pendingUsers", "Pending users", users.get("pending"), "ATTENTION"),
                insightMetric("governance.pendingDataRequests", "Pending data requests", requests.get("pending"), "ATTENTION"),
                insightMetric("infrastructure.activeAgents", "Active agents", agents.get("active"), "HEALTH"),
                insightMetric("usage.failedExecutionsToday", "Failed executions today", executions.get("failed"), "ATTENTION"),
                insightMetric("downloads.failedToday", "Failed downloads today", downloads.get("failed"), "ATTENTION"),
                insightMetric("downloads.mismatchesToday", "Checksum mismatches today", downloads.get("mismatched"), "CRITICAL"));

        long pending = number(queries.get("pending")) + number(users.get("pending")) + number(requests.get("pending"));
        long failures = number(executions.get("failed")) + number(downloads.get("failed"));
        long mismatches = number(downloads.get("mismatched"));
        List<String> observations = new ArrayList<>();
        if (mismatches > 0) observations.add(mismatches + " checksum mismatch" + plural(mismatches) + " require immediate review");
        if (failures > 0) observations.add(failures + " execution/download failure" + plural(failures) + " occurred today");
        if (pending > 0) observations.add(pending + " governance item" + plural(pending) + " are awaiting action");
        if (number(agents.get("active")) == 0) observations.add("no execution agents are currently active");
        if (observations.isEmpty()) observations.add("no actionable exception is present in the approved live metrics");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", "Current DOORS insights:\n- " + String.join(".\n- ", observations) + ".");
        result.put("answerType", "LIVE_OPERATIONAL");
        result.put("insightType", "OPERATIONAL_INSIGHTS");
        result.put("metrics", metrics);
        result.put("executionData", metrics);
        result.put("capturedAt", snapshot.get("capturedAt"));
        result.put("knowledgeSnippetsMatched", 0);
        result.put("grounded", true);
        result.put("refined", false);
        result.put("executionDisabled", true);
        return result;
    }

    private Map<String, Object> insightMetric(String id, String label, Object value, String tone) {
        return Map.of("id", id, "label", label, "value", value == null ? 0 : value, "tone", tone);
    }

    private static String plural(long count) { return count == 1 ? "" : "s"; }
    private static String label(String value) { return value.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT); }
    private static String normalizeRole(String value) {
        return value.toUpperCase(Locale.ROOT).replaceFirst("^ROLE_", "").replaceAll("[^A-Z]", "");
    }
}
