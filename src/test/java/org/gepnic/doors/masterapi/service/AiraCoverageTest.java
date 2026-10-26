package org.gepnic.doors.masterapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gepnic.doors.masterapi.config.AiraProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiraCoverageTest {

    @Test
    void routesRepresentativeLiveAndDocumentationQuestions() {
        List<String> live = List.of(
                "How many documents were downloaded today?",
                "How many queries are approved?",
                "Are any agents inactive?",
                "What needs attention today?",
                "What is the current execution failure rate?"
        );
        live.forEach(question -> assertNotEquals(AiraOperationalIntent.Source.DOCUMENTATION,
                AiraOperationalIntent.classify(question).source(), question));

        List<String> documentation = List.of(
                "Explain the rate limit feature of DOORS",
                "What are the three planes of DOORS?",
                "How do I onboard a write service?",
                "Explain automatic record versioning",
                "How are document downloads protected?"
        );
        documentation.forEach(question -> assertEquals(AiraOperationalIntent.Source.DOCUMENTATION,
                AiraOperationalIntent.classify(question).source(), question));
    }

    @Test
    void criticalFeatureAndWorkflowQuestionsHaveGovernedOfflineCoverage() {
        AiraProperties properties = new AiraProperties();
        properties.setEnabled(false);
        properties.setRagMaxResults(6);
        AiraKnowledgeService knowledge = new AiraKnowledgeService(
                properties, new DefaultResourceLoader(), new ObjectMapper());
        knowledge.init();

        assertCovered(knowledge, "Explain the rate limit feature", "429");
        assertCovered(knowledge, "What are the three planes?", "Admin Plane");
        assertCovered(knowledge, "How do I onboard a write service?", "sandbox");
        assertCovered(knowledge, "How does automatic record versioning work?", "UNCHANGED");
        assertCovered(knowledge, "How do I troubleshoot a failed API request?", "trace ID");
        assertCovered(knowledge, "How are document downloads verified?", "SHA-256");

        List<String> writeSteps = knowledge.findRelevantKnowledge(
                "Share me steps for creating a write service API", 6, 0.5);
        String writeText = String.join("\n", writeSteps);
        assertTrue(writeText.contains("Write-service onboarding"), writeText);
        assertFalse(writeText.contains("Dual-Layer Cryptographic Envelope"), writeText);
        assertFalse(writeText.contains("The Three-Plane Architecture"), writeText);

        List<String> planes = knowledge.findRelevantKnowledge(
                "Explain different planes of DOORS", 6, 0.5);
        String planeText = String.join("\n", planes);
        assertTrue(planeText.contains("Three planes"), planeText);
        assertFalse(planeText.contains("> Rate limiting"), planeText);

        List<String> readClient = knowledge.findRelevantKnowledge(
                "Share steps for creating Read Service API client", 6, 0.5);
        String readClientText = String.join("\n", readClient);
        assertTrue(readClientText.contains("Read-service API-client onboarding"), readClientText);
        assertFalse(readClientText.contains("Write-service onboarding"), readClientText);
    }

    private static void assertCovered(AiraKnowledgeService knowledge, String question, String expected) {
        List<String> results = knowledge.findRelevantKnowledge(question, 6, 0.5);
        assertFalse(results.isEmpty(), question);
        assertTrue(results.stream().anyMatch(value -> value.contains(expected)),
                question + " should retrieve " + expected + ": " + results);
    }
}
