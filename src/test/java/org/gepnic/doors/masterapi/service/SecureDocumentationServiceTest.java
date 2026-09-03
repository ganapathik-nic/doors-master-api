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
        assertEquals(21, documents.size());
        assertTrue(documents.stream().anyMatch(value -> value.id().equals("hosting-deployment-operations")));
        assertTrue(documents.stream().anyMatch(value -> value.id().equals("doors-process-flow-flyer")));
        assertTrue(service.resolve("doors-process-flow-flyer", auth("DATAMANAGER")).resource().exists());
        assertTrue(documents.stream().anyMatch(value -> value.id().equals("end-to-end-encryption-process") && value.version().equals("2.0")));
    }

    @Test
    void externalUserReceivesOnlySharedAndRoleManuals() {
        var documents = service.visibleCatalogue(auth("ROLE_EXTERNAL"));
        assertEquals(6, documents.size());
        assertTrue(documents.stream().anyMatch(value -> value.id().equals("external-user-manual")));
        assertTrue(documents.stream().anyMatch(value -> value.id().equals("doors-rbac-security-flyer")));
    }

    @Test
    void unauthorizedDocumentIsConcealedAsNotFound() {
        assertThrows(ResponseStatusException.class,
                () -> service.resolve("security-architecture-controls", auth("DEVELOPER")));
    }

    @Test
    void traversalStyleIdentifiersAreRejected() {
        assertThrows(ResponseStatusException.class,
                () -> service.resolve("../application.yml", auth("DATAMANAGER")));
    }

    private UsernamePasswordAuthenticationToken auth(String authority) {
        return new UsernamePasswordAuthenticationToken("tester", null,
                List.of(new SimpleGrantedAuthority(authority)));
    }
}
