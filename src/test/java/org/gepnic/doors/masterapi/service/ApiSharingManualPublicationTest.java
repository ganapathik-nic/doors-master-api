package org.gepnic.doors.masterapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gepnic.doors.masterapi.config.AiraProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ApiSharingManualPublicationTest {
    private AiraKnowledgeService knowledge() {
        var properties = new AiraProperties();
        properties.setEnabled(true);
        return new AiraKnowledgeService(properties, new DefaultResourceLoader(), new ObjectMapper());
    }

    @Test
    void allManualPagesAreSearchableAndRetainPageCitations() throws Exception {
        var chunks = knowledge().parsePdfToSegments(new ClassPathResource("secure-docs/api-data-sharing-visual-manual.pdf"));
        Set<String> pages = chunks.stream().map(chunk -> chunk.text().split("\\| PAGE: ")[1].split(" \\|")[0])
                .collect(Collectors.toSet());
        assertEquals(27, pages.size());
        String content = chunks.stream().map(chunk -> chunk.text()).collect(Collectors.joining("\n"));
        assertTrue(content.contains("readme-enduser-uat.txt"));
        assertTrue(content.contains("doors-production-mail.txt"));
        assertTrue(content.contains("Production trust"));
        assertTrue(content.contains("Manage Agent mappings"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DOORS_PUBLISH_API_MANUAL", matches = "true")
    void rebuildPublishedKnowledgeAndCheckNoviceQuestions() {
        var service = knowledge();
        int count = service.reindexKnowledge();
        assertTrue(count > 0);
        for (String prompt : new String[] {
                "How does a Data Manager register an API client and map an approved query and Agent?",
                "How do I set up and build the UAT Shared-Credential SDK?",
                "Which Production Framework SDK files and README should I send to the recipient?",
                "What should the DOORS UAT handoff email contain?"
        }) {
            var matches = service.findRelevantKnowledge(prompt);
            System.out.println("MANUAL RETRIEVAL: " + prompt);
            matches.forEach(value -> System.out.println(value.substring(0, Math.min(value.indexOf(']') + 1, value.length()))));
            assertTrue(matches.stream().anyMatch(value -> value.contains("api-data-sharing-visual-manual.pdf")),
                    "New manual should support: " + prompt);
        }
    }
}
