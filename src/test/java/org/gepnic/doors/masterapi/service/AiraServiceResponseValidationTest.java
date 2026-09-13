package org.gepnic.doors.masterapi.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

class AiraServiceResponseValidationTest {

    @Test
    void rejectsBlankAndFragmentResponses() {
        assertTrue(AiraService.isIncompleteAnswer(null));
        assertTrue(AiraService.isIncompleteAnswer(""));
        assertTrue(AiraService.isIncompleteAnswer("Note:"));
        assertTrue(AiraService.isIncompleteAnswer("A short unfinished response"));
    }

    @Test
    void acceptsCompleteGroundedResponse() {
        assertFalse(AiraService.isIncompleteAnswer(
                "DOORS separates authoring, approval, and consumption responsibilities across its defined roles."));
    }

    @Test
    void refinedKnowledgeAnswerRequiresSourceCitation() {
        String answer = "DOORS separates authoring, approval, and consumption responsibilities across its defined roles.";
        assertTrue(AiraService.isUnacceptableAnswer(answer, true));
        assertFalse(AiraService.isUnacceptableAnswer(answer + "\nSources: DOORS Platform Operational Guide", true));
    }

    @Test
    void includesLiveSnapshotOnlyForLiveStateQuestions() {
        assertFalse(AiraService.requiresLiveSnapshot("Explain RBAC implementation in DOORS"));
        assertTrue(AiraService.requiresLiveSnapshot("How many approved queries are currently active?"));
    }

    @Test
    void classifiesOperationalDocumentationAndMixedQuestions() {
        assertEquals(AiraOperationalIntent.Source.OPERATIONAL,
                AiraOperationalIntent.classify("Any pending queries for approval?").source());
        assertEquals(AiraOperationalIntent.Source.DOCUMENTATION,
                AiraOperationalIntent.classify("Explain the query approval procedure").source());
        assertEquals(AiraOperationalIntent.Source.MIXED,
                AiraOperationalIntent.classify("How many queries are pending and who approves them?").source());
        assertEquals(AiraOperationalIntent.Period.TODAY,
                AiraOperationalIntent.classify("How many executions failed today?").period());
        AiraOperationalIntent downloads = AiraOperationalIntent.classify("How many documents were downloaded today?");
        assertEquals(AiraOperationalIntent.Source.OPERATIONAL, downloads.source());
        assertEquals(AiraOperationalIntent.Domain.DOCUMENT_DOWNLOADS, downloads.domain());
        assertEquals(AiraOperationalIntent.Period.TODAY, downloads.period());
        AiraOperationalIntent insights = AiraOperationalIntent.classify("Summarize current DOORS usage and insights");
        assertEquals(AiraOperationalIntent.Source.OPERATIONAL, insights.source());
        assertEquals(AiraOperationalIntent.Domain.INSIGHTS, insights.domain());
    }

    @Test
    void boundsRetrievedContextForLocalCpuInference() {
        List<String> result = AiraService.boundKnowledgeContext(List.of("a".repeat(3_000), "b".repeat(3_000)));
        assertEquals(2, result.size());
        assertEquals(3_500, result.stream().mapToInt(String::length).sum());
    }

    @Test
    void extractiveAnswerContainsOnlyRetrievedKnowledge() {
        String answer = AiraService.buildExtractiveKnowledgeAnswer(List.of(
                "[SOURCE: roles.pdf | PAGE: 2] " + "Data Manager governs approvals. ".repeat(20),
                "[SOURCE: roles.pdf | PAGE: 3] " + "Developer authors templates but cannot approve them. ".repeat(15)));
        assertTrue(answer.contains("Data Manager governs approvals"));
        assertTrue(answer.contains("Developer authors templates"));
        assertTrue(answer.contains("roles.pdf, page 2"));
    }

    @Test
    void recognizesAndExpandsBroadSecurityOverviewQuestions() {
        String prompt = "Explain security features of DOORS";
        assertTrue(AiraService.isSecurityOverview(prompt));
        String expanded = AiraService.securityRetrievalQuery(prompt).toLowerCase();
        assertTrue(expanded.contains("segregation of duties"));
        assertTrue(expanded.contains("three plane"));
        assertTrue(expanded.contains("digital signatures"));
        assertTrue(expanded.contains("fail closed"));
    }

    @Test
    void securityOverviewPrioritizesCanonicalControlsAndSuppressesGenericManuals() {
        List<String> ranked = AiraService.prioritizeKnowledge("Explain security features of DOORS", List.of(
                "[SOURCE: secure-docs/data-viewer-manual.pdf | PAGE: 1] Responsible use and task guidance.",
                "[SOURCE: secure-docs/security-architecture-controls.pdf | PAGE: 1] Three-plane isolation and fail-closed access controls.",
                "[SOURCE: secure-docs/platform-reference.pdf | PAGE: 9] Controlled copy guidance.",
                "[SOURCE: secure-docs/rbac-segregation-duties.pdf | PAGE: 2] Role-based access and segregation of duties.",
                "[SOURCE: secure-docs/end-to-end-encryption-process.pdf | PAGE: 3] TLS, AES, RSA and digital signatures protect payloads."));

        assertEquals(3, ranked.size());
        assertTrue(ranked.get(0).contains("security-architecture-controls.pdf"));
        assertTrue(ranked.get(1).contains("rbac-segregation-duties.pdf"));
        assertTrue(ranked.get(2).contains("end-to-end-encryption-process.pdf"));
        assertTrue(ranked.stream().noneMatch(value -> value.contains("manual.pdf") || value.contains("platform-reference.pdf")));
    }

    @Test
    void passwordPolicyQuestionsUseOnlyTheCanonicalCurrentPolicy() {
        String prompt = "Explain the DOORS password policy";
        assertTrue(AiraService.isPasswordPolicyQuestion(prompt));
        assertTrue(AiraService.passwordPolicyRetrievalQuery(prompt).contains("minimum 12 maximum 128"));

        List<String> ranked = AiraService.prioritizeKnowledge(prompt, List.of(
                "[SOURCE: secure-docs/platform-reference.pdf | PAGE: 13] Default password doors@example.",
                "[SOURCE: secure-docs/security-admin-manual.pdf | PAGE: 3] Reset the user password.",
                "[SOURCE: secure-docs/password-policy-mfa.pdf | PAGE: 2] Password storage uses BCrypt over SHA-256.",
                "[SOURCE: secure-docs/password-policy-mfa.pdf | PAGE: 1] Minimum 12 characters, maximum 128, uppercase, lowercase, number, special character and no whitespace."));

        assertEquals(2, ranked.size());
        assertTrue(ranked.get(0).contains("PAGE: 1"));
        assertTrue(ranked.stream().noneMatch(value -> value.contains("platform-reference.pdf")
                || value.contains("security-admin-manual.pdf") || value.contains("Default password")));
    }

    @Test
    void credentialBearingLegacyChunksNeverReachTheModel() {
        List<String> safe = AiraService.removeSensitiveKnowledge(List.of(
                "Legacy staging users use default password doors@nic.",
                "The current password policy requires at least 12 characters."));
        assertEquals(1, safe.size());
        assertTrue(safe.get(0).contains("12 characters"));
    }
}
