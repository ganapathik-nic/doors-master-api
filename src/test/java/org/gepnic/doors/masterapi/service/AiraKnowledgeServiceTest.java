package org.gepnic.doors.masterapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import org.gepnic.doors.masterapi.config.AiraProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiraKnowledgeServiceTest {

    private AiraProperties properties;
    private AiraKnowledgeService knowledgeService;

    @BeforeEach
    void setUp() {
        properties = new AiraProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:11434");
        properties.setModel("qwen2.5:1.5b");
        properties.setEmbeddingModel("nomic-embed-text");
        properties.setVectorStorePath("security/knowledge/doors-vector-store.json");
        properties.setRagMaxResults(4);
        properties.setRagMinScore(0.50);

        knowledgeService = new AiraKnowledgeService(properties, new DefaultResourceLoader(), new ObjectMapper());
    }

    @Test
    void testMarkdownChunkingPreservesHierarchy() {
        String sampleMarkdown = """
                # DOORS Operational Manual
                
                ## Section 1: Plane Separation
                DOORS enforces strict separation across planes.
                
                ### Admin Plane
                Accessible only by DataManager and ADMIN roles.
                
                ### External Plane
                Accessible by External users.
                
                ## Section 2: PKI Cryptography
                All payloads are protected with AES-256 and RSA.
                """;

        List<TextSegment> segments = knowledgeService.parseMarkdownToSegments(sampleMarkdown);

        assertNotNull(segments);
        assertEquals(4, segments.size());

        // Check first segment
        assertTrue(segments.get(0).text().contains("[DOORS Operational Manual] > Section 1: Plane Separation"));
        assertTrue(segments.get(0).text().contains("DOORS enforces strict separation across planes."));

        // Check subsection 1
        assertTrue(segments.get(1).text().contains("Section 1: Plane Separation > Admin Plane"));
        assertTrue(segments.get(1).text().contains("Accessible only by DataManager"));

        // Check subsection 2
        assertTrue(segments.get(2).text().contains("Section 1: Plane Separation > External Plane"));
        assertTrue(segments.get(2).text().contains("Accessible by External users."));

        // Check section 2
        assertTrue(segments.get(3).text().contains("Section 2: PKI Cryptography"));
        assertTrue(segments.get(3).text().contains("All payloads are protected with AES-256 and RSA."));
    }

    @Test
    void everySecuredPdfProducesAuditableChunks() throws Exception {
        var resolver = new PathMatchingResourcePatternResolver();
        var pdfs = resolver.getResources("classpath*:secure-docs/*.pdf");
        assertEquals(23, pdfs.length, "All catalogue PDFs must be present");

        for (var pdf : pdfs) {
            List<TextSegment> segments = knowledgeService.parsePdfToSegments(pdf);
            assertFalse(segments.isEmpty(), pdf.getFilename() + " should produce searchable text");
            assertTrue(segments.stream().allMatch(segment ->
                            segment.text().startsWith("[SOURCE: secure-docs/" + pdf.getFilename())),
                    pdf.getFilename() + " chunks should retain their source identity");
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "DOORS_RUN_INTEGRATION_TESTS", matches = "true")
    void testVectorStoreLoadingAndSemanticSearch() {
        int indexedSegments = knowledgeService.reindexKnowledge();

        assertTrue(knowledgeService.isReady(), "Knowledge base should be ready after loading from disk");
        assertEquals(indexedSegments, knowledgeService.getSegmentCount());
        assertTrue(knowledgeService.getSegmentCount() >= 15, "Expected at least 15 indexed operational segments");

        List<String> passwordSnippets = knowledgeService.findRelevantKnowledge(
                "What is the DOORS password policy and how does MFA work?", 8, 0.35);
        assertFalse(passwordSnippets.isEmpty(), "Password and MFA guidance should be retrievable");
        assertTrue(passwordSnippets.stream().anyMatch(s -> s.contains("password-policy-mfa.pdf")),
                "Password query should retrieve the dedicated guide: " + passwordSnippets);
        assertTrue(passwordSnippets.stream().anyMatch(s -> s.contains("12 characters") || s.contains("TOTP")),
                "Password query should return concrete policy or MFA controls: " + passwordSnippets);

        // 1. Search for Three Planes
        List<String> planeSnippets = knowledgeService.findRelevantKnowledge("What are the three planes in DOORS architecture?");
        assertFalse(planeSnippets.isEmpty(), "Should retrieve Three-Plane Architecture snippets");
        assertTrue(planeSnippets.stream().anyMatch(s -> s.contains("Three-Plane") || s.contains("Admin Plane")),
                "Snippet should mention Three-Plane Architecture or Admin Plane");

        // 2. Search for Data Manager Role
        List<String> roleSnippets = knowledgeService.findRelevantKnowledge("What are the duties and boundaries of a Data Manager?");
        assertFalse(roleSnippets.isEmpty(), "Should retrieve Data Manager role documentation");
        assertTrue(roleSnippets.stream().anyMatch(s -> s.contains("DataManager") || s.contains("Data Manager")),
                "Snippet should mention Data Manager");

        List<String> rbacSnippets = knowledgeService.findRelevantKnowledge("Explain RBAC implementation in DOORS");
        assertTrue(rbacSnippets.stream().anyMatch(s -> s.contains("DataManager") || s.contains("Data Manager")),
                "RBAC query should retrieve DOORS role definitions: " + rbacSnippets);

        // 3. Search for Cryptography Envelope
        List<String> cryptoSnippets = knowledgeService.findRelevantKnowledge("How does the dual-layer cryptographic envelope work in DOORS?");
        assertFalse(cryptoSnippets.isEmpty(), "Should retrieve PKI and AES envelope documentation");
        assertTrue(cryptoSnippets.stream().anyMatch(s -> s.contains("AES") || s.contains("RSA")),
                "Snippet should mention AES or RSA encryption");

        String securityPrompt = "Explain security features of DOORS";
        List<String> securitySnippets = AiraService.prioritizeKnowledge(securityPrompt,
                knowledgeService.findRelevantKnowledge(AiraService.securityRetrievalQuery(securityPrompt), 12, 0.42));
        assertFalse(securitySnippets.isEmpty(), "Security overview should retrieve canonical control documentation");
        assertTrue(securitySnippets.stream().anyMatch(value -> value.contains("security-architecture-controls.pdf")
                        || value.contains("rbac-segregation-duties.pdf")
                        || value.contains("end-to-end-encryption-process.pdf")),
                "Security overview should prioritize architecture, RBAC, or encryption sources: " + securitySnippets);
        assertTrue(securitySnippets.stream().noneMatch(value -> value.contains("data-viewer-manual.pdf")
                        || value.contains("platform-reference.pdf")
                        || value.contains("troubleshooting-runbooks.pdf")),
                "Generic manuals and troubleshooting sources must not dominate a security overview: " + securitySnippets);
    }
}
