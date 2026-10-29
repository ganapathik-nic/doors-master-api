package org.gepnic.doors.masterapi.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecureDocumentationServiceTest {
    private final SecureDocumentationService service = new SecureDocumentationService();

    @Test
    void dataManagerReceivesCompleteCatalogue() {
        var documents = service.visibleCatalogue(auth("DATAMANAGER"));
        assertEquals(32, documents.size());
        assertTrue(documents.stream().anyMatch(value -> value.id().equals("hosting-deployment-operations")));
        assertTrue(documents.stream().anyMatch(value -> value.id().equals("doors-process-flow-flyer")));
        assertTrue(service.resolve("doors-process-flow-flyer", auth("DATAMANAGER")).resource().exists());
        assertTrue(documents.stream().anyMatch(value -> value.id().equals("end-to-end-encryption-process") && value.version().equals("2.0")));
        assertTrue(documents.stream().anyMatch(value -> value.id().equals("aira-architecture-operations") && value.version().equals("1.2")));
        assertTrue(service.resolve("aira-architecture-operations", auth("DATAMANAGER")).resource().exists());
        assertTrue(service.resolve("password-policy-mfa", auth("DATAMANAGER")).resource().exists());
        assertTrue(service.resolve("api-data-sharing-visual-manual", auth("DATAMANAGER")).resource().exists());
        assertTrue(service.resolve("software-requirements-specification", auth("DATAMANAGER")).resource().exists());
        assertTrue(service.resolve("database-design-document", auth("DATAMANAGER")).resource().exists());
        assertTrue(service.resolve("functional-user-administrator-manual", auth("DATAMANAGER")).resource().exists());
        assertTrue(service.resolve("software-design-description", auth("DATAMANAGER")).resource().exists());
        for (String id : List.of("software-requirements-specification", "database-design-document",
                "functional-user-administrator-manual", "software-design-description")) {
            assertTrue(documents.stream().anyMatch(value -> value.id().equals(id) && value.category().equals("System")));
        }
    }

    @Test
    void externalUserReceivesOnlySharedAndRoleManuals() {
        var documents = service.visibleCatalogue(auth("ROLE_EXTERNAL"));
        assertEquals(9, documents.size());
        assertTrue(documents.stream().anyMatch(value -> value.id().equals("external-user-manual")));
        assertTrue(documents.stream().anyMatch(value -> value.id().equals("doors-rbac-security-flyer")));
        assertTrue(documents.stream().anyMatch(value -> value.id().equals("password-policy-mfa")));
        assertTrue(documents.stream().anyMatch(value -> value.id().equals("functional-user-administrator-manual")));
        assertThrows(ResponseStatusException.class,
                () -> service.resolve("software-design-description", auth("EXTERNAL")));
    }

    @Test
    void promoAndIntegrationGuideUseTheirIntendedAudiencesAndFormats() {
        for (String role : List.of("EXTERNAL", "APIUSER", "DEVELOPER", "DATAVIEWER", "SECURITYADMIN", "DATAMANAGER")) {
            var promo = service.resolve("doors-platform-promo", auth(role));
            assertEquals("video/mp4", promo.summary().mediaType());
            assertEquals("doors-platform-promo.mp4", promo.summary().fileName());
            assertTrue(promo.resource().exists());
        }
        for (String role : List.of("APIUSER", "DEVELOPER", "SECURITYADMIN", "DATAMANAGER")) {
            assertTrue(service.resolve("rate-policy-configuration-implementation", auth(role)).resource().exists());
            var guide = service.resolve("gepnic-sasci-integration", auth(role));
            assertEquals("application/pdf", guide.summary().mediaType());
            assertTrue(guide.resource().exists());
            var securityGuide = service.resolve("gepnic-sasci-secured-data-sharing", auth(role));
            assertEquals("application/pdf", securityGuide.summary().mediaType());
            assertEquals("gepnic-sasci-secured-data-sharing.pdf", securityGuide.summary().fileName());
            assertTrue(securityGuide.resource().exists());
            assertTrue(service.visibleCatalogue(auth(role)).stream()
                    .anyMatch(doc -> doc.id().equals("gepnic-sasci-secured-data-sharing")));
        }
        for (String role : List.of("EXTERNAL", "DATAVIEWER")) {
            assertThrows(ResponseStatusException.class, () -> service.resolve("rate-policy-configuration-implementation", auth(role)));
            assertThrows(ResponseStatusException.class, () -> service.resolve("gepnic-sasci-integration", auth(role)));
            assertThrows(ResponseStatusException.class, () -> service.resolve("gepnic-sasci-secured-data-sharing", auth(role)));
            assertTrue(service.visibleCatalogue(auth(role)).stream()
                    .noneMatch(doc -> doc.id().equals("gepnic-sasci-secured-data-sharing")));
        }
        assertThrows(ResponseStatusException.class, () -> service.resolve("doors-platform-promo", null));
        assertThrows(ResponseStatusException.class, () -> service.resolve("gepnic-sasci-secured-data-sharing", null));
    }

    @Test
    void unauthorizedDocumentIsConcealedAsNotFound() {
        assertThrows(ResponseStatusException.class,
                () -> service.resolve("security-architecture-controls", auth("DEVELOPER")));
        for (String role : List.of("EXTERNAL", "APIUSER", "DEVELOPER", "DATAVIEWER", "SECURITYADMIN")) {
            assertThrows(ResponseStatusException.class,
                    () -> service.resolve("api-data-sharing-visual-manual", auth(role)));
        }
    }

    @Test
    void traversalStyleIdentifiersAreRejected() {
        assertThrows(ResponseStatusException.class,
                () -> service.resolve("../application.yml", auth("DATAMANAGER")));
    }

    @Test
    void walkthroughsUseManualPermissionsAndFixedResources() throws Exception {
        assertEquals(1, service.visibleWalkthroughs(auth("DATAMANAGER")).size());
        var resource = service.resolveWalkthrough("api-data-sharing", auth("DATAMANAGER"));
        var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resource.getInputStream());
        assertEquals(35, tree.get("steps").size());
        for (String role : List.of("EXTERNAL", "APIUSER", "DEVELOPER", "DATAVIEWER", "SECURITYADMIN")) {
            assertTrue(service.visibleWalkthroughs(auth(role)).isEmpty());
            assertThrows(ResponseStatusException.class, () -> service.resolveWalkthrough("api-data-sharing", auth(role)));
        }
        assertThrows(ResponseStatusException.class, () -> service.resolveWalkthrough("../application", auth("DATAMANAGER")));
        assertThrows(ResponseStatusException.class, () -> service.resolveWalkthrough("api-data-sharing", null));
    }

    private UsernamePasswordAuthenticationToken auth(String authority) {
        return new UsernamePasswordAuthenticationToken("tester", null,
                List.of(new SimpleGrantedAuthority(authority)));
    }
}
