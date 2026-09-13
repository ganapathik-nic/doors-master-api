package org.gepnic.doors.masterapi.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiraExtractiveFormatterTest {

    @Test
    void formatsRoleMatrixAndRemovesPdfBoilerplate() {
        String answer = AiraExtractiveFormatter.format(List.of("""
                [SOURCE: secure-docs/rbac-segregation-duties.pdf | PAGE: 2 | CHUNK: 1]
                DOORS DOCUMENTATION HUB DOORS-RBAC-SEGREGATION-DUTIES
                Document control
                Field Value
                Owner DOORS Product Governance

                Role matrix
                Role Permitted purpose Excluded boundary
                Data Manager Governance, registries, mappings, audits Unreviewed arbitrary SQL
                Developer Template authoring and dry runs Final approval of own template

                Segregation rules
                • Author and approver should be distinct for material production templates.
                Controlled copy - verify the current version in DOORS
                """));

        assertTrue(answer.contains("Role matrix"));
        assertTrue(answer.contains("- Data Manager: Governance"));
        assertTrue(answer.contains("- Developer: Template authoring"));
        assertTrue(answer.contains("rbac-segregation-duties.pdf, page 2"));
        assertFalse(answer.contains("Document control"));
        assertFalse(answer.contains("Controlled copy"));
    }

    @Test
    void removesEscapesAndOrdersPagesForTechnicalReferences() {
        String pageTwo = """
                [SOURCE: secure-docs/ui-backend-encryption-process.pdf | PAGE: 2 | CHUNK: 1]
                DOORS | Controlled Technical Reference
                3\\. Master API browser-response protection
                1\\. Authorize. Validate the portal session.
                NIC eProcurement Project | Version 2.0 | 30 August 2026
                """;
        String pageOne = """
                [SOURCE: secure-docs/ui-backend-encryption-process.pdf | PAGE: 1 | CHUNK: 1]
                UI-to-Master API Encryption and Decryption
                \\- Browser uses the Secure, HttpOnly DOORS\\_SESSION cookie.
                \\- Never include \\<secrets> in logs.
                """;

        String answer = AiraExtractiveFormatter.format(List.of(pageTwo, pageOne));

        assertTrue(answer.indexOf("UI-to-Master") < answer.indexOf("Master API browser-response"));
        assertTrue(answer.contains("DOORS_SESSION"));
        assertTrue(answer.contains("- Browser uses"));
        assertFalse(answer.contains("\\\\"));
        assertFalse(answer.contains("Controlled Technical Reference"));
        assertFalse(answer.contains("NIC eProcurement Project"));
    }
}
