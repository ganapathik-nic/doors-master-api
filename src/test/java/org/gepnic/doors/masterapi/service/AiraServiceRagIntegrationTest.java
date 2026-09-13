package org.gepnic.doors.masterapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gepnic.doors.masterapi.config.AiraProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Map;
import java.util.Set;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "DOORS_RUN_INTEGRATION_TESTS", matches = "true")
class AiraServiceRagIntegrationTest {

    private AiraService airaService;

    static class StubAiraContextService extends AiraContextService {
        public StubAiraContextService() {
            super(null, null);
        }

        @Override
        public String buildOperationalContext() {
            return """
                    DOORS OPERATIONAL SNAPSHOT
                    Active execution agents: 4
                    Approved report catalogue: 17
                    - Report: ep_tenders_active | category=Procurement | defaultAgent=AGENT-TN-01 | parameters=[state_code] | description=Active Tenders List
                    """;
        }

        @Override
        public Map<String, Object> buildOperationalSnapshot() {
            return Map.of(
                    "capturedAt", "2026-09-08T12:00:00+05:30",
                    "queries", Map.of("total", 20L, "pending", 2L, "approved", 17L,
                            "rejected", 1L, "active", 17L, "inactive", 3L, "activeApproved", 17L),
                    "agents", Map.of("total", 4L, "active", 4L, "inactive", 0L, "sandbox", 1L, "production", 3L),
                    "users", Map.of("total", 0L, "active", 0L, "pending", 0L, "rejected", 0L),
                    "dataRequests", Map.of("total", 0L, "pending", 0L, "approved", 0L, "rejected", 0L,
                            "completed", 0L, "failed", 0L),
                    "apiClients", Map.of("total", 0L, "active", 0L, "inactive", 0L),
                    "executions", Map.of("today", Map.of("total", 0L, "successful", 0L, "failed", 0L),
                            "sevenDays", Map.of("total", 0L, "successful", 0L, "failed", 0L),
                            "thirtyDays", Map.of("total", 0L, "successful", 0L, "failed", 0L)),
                    "documentDownloads", Map.of(
                            "today", Map.of("total", 9L, "verified", 7L, "failed", 1L,
                                    "mismatched", 0L, "pending", 1L, "bytes", 4096L),
                            "sevenDays", Map.of("total", 20L), "thirtyDays", Map.of("total", 40L)));
        }
    }

    @BeforeEach
    void setUp() {
        AiraProperties properties = new AiraProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:11434");
        properties.setModel("qwen2.5:1.5b");
        properties.setEmbeddingModel("nomic-embed-text");
        properties.setTimeoutSeconds(180);
        properties.setMaxPromptChars(4000);
        properties.setVectorStorePath("security/knowledge/doors-vector-store.json");
        properties.setRagMaxResults(4);
        properties.setRagMinScore(0.50);

        AiraKnowledgeService knowledgeService = new AiraKnowledgeService(
                properties, new DefaultResourceLoader(), new ObjectMapper());
        knowledgeService.init();

        AiraContextService stubContextService = new StubAiraContextService();

        airaService = new AiraService(properties, stubContextService, knowledgeService, null, new ObjectMapper());
    }

    @Test
    void testStatusIncludesKnowledgeBaseMetrics() {
        Map<String, Object> status = airaService.status();
        assertNotNull(status);
        assertEquals(true, status.get("enabled"));
        assertEquals("qwen2.5:1.5b", status.get("model"));
        assertEquals("nomic-embed-text", status.get("embeddingModel"));
        assertEquals(true, status.get("knowledgeBaseReady"));
        assertTrue((Integer) status.get("knowledgeSegmentsCount") >= 15);
    }

    @Test
    void testChatAnswersProceduralQuestionWithVectorKnowledge() {
        Map<String, Object> response = airaService.chat(
                "datamanager@test.nic.in",
                "Explain RBAC implementation in DOORS",
                "trace-test-1",
                "127.0.0.1"
        );

        assertNotNull(response);
        String answer = (String) response.get("answer");
        assertNotNull(answer);
        assertFalse(answer.isBlank());

        // Verify that knowledge snippets were matched from vector store
        Integer matchedSnippets = (Integer) response.get("knowledgeSnippetsMatched");
        assertNotNull(matchedSnippets);
        assertTrue(matchedSnippets > 0, "Vector store should match at least 1 snippet for Three Planes");

        // A heading or generic definition is not a valid grounded answer. Verify
        // that the model used DOORS-specific role boundaries from retrieved chunks.
        String lower = answer.toLowerCase();
        assertFalse(AiraService.isIncompleteAnswer(answer), "Answer must be complete: " + answer);
        assertTrue(lower.contains("data manager") && lower.contains("developer")
                        && (lower.contains("data viewer") || lower.contains("external")),
                "Answer should explain DOORS-specific RBAC roles: " + answer);
        assertTrue(lower.contains("sources:"), "Grounded answer should identify its source: " + answer);
    }

    @Test
    void testChatAnswersLiveDatabaseCountQuestion() {
        Map<String, Object> response = airaService.chat(
                "datamanager@test.nic.in",
                "How many queries are approved in the system?",
                "trace-test-2",
                "127.0.0.1"
        );

        assertNotNull(response);
        String answer = (String) response.get("answer");
        assertNotNull(answer);
        assertFalse(answer.isBlank());

        // Verify answer uses the snapshot count
        assertTrue(answer.contains("17"), "Answer should mention the 17 approved queries from the snapshot: " + answer);
        assertEquals("LIVE_OPERATIONAL", response.get("answerType"));
        assertEquals(0, response.get("knowledgeSnippetsMatched"));
        assertNotNull(response.get("capturedAt"));
    }

    @Test
    void testPendingQueryCountUsesLiveSnapshotWithoutDocumentRetrieval() {
        Map<String, Object> response = airaService.chat("datamanager@test.nic.in",
                "Any pending queries for approval?", "trace-test-3", "127.0.0.1");
        assertTrue(((String) response.get("answer")).contains("2"));
        assertEquals("LIVE_OPERATIONAL", response.get("answerType"));
        assertEquals(0, response.get("knowledgeSnippetsMatched"));
    }

    @Test
    void testSensitiveOperationalMetricsEnforceRoleBoundary() {
        assertThrows(SecurityException.class, () -> airaService.chat("external@test.nic.in",
                Set.of("ROLE_EXTERNAL"), "How many users are currently active?",
                "trace-test-4", "127.0.0.1", false));
    }

    @Test
    void testDocumentDownloadsTodayRoutesToLiveMetrics() {
        Map<String, Object> response = airaService.chat("datamanager@test.nic.in",
                "How many documents were downloaded today?", "trace-test-5", "127.0.0.1");
        assertTrue(((String) response.get("answer")).contains("9"));
        assertEquals("LIVE_OPERATIONAL", response.get("answerType"));
        assertEquals(0, response.get("knowledgeSnippetsMatched"));
    }

    @Test
    void testOperationalInsightsAreGeneratedFromLiveMetrics() {
        Map<String, Object> response = airaService.chat("datamanager@test.nic.in",
                "What needs attention today? Give me operational insights.",
                "trace-test-6", "127.0.0.1");
        assertEquals("LIVE_OPERATIONAL", response.get("answerType"));
        assertEquals("OPERATIONAL_INSIGHTS", response.get("insightType"));
        assertTrue(((String) response.get("answer")).contains("governance item"));
        assertFalse(((List<?>) response.get("metrics")).isEmpty());
    }
}
