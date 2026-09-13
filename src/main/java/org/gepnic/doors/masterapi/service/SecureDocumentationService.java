package org.gepnic.doors.masterapi.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class SecureDocumentationService {

    private static final String DATAMANAGER = "DATAMANAGER";
    private static final String DEVELOPER = "DEVELOPER";
    private static final String DATAVIEWER = "DATAVIEWER";
    private static final String EXTERNAL = "EXTERNAL";
    private static final String APIUSER = "APIUSER";
    private static final String SECURITYADMIN = "SECURITYADMIN";
    private static final Set<String> ALL = Set.of(DATAMANAGER, DEVELOPER, DATAVIEWER, EXTERNAL, APIUSER, SECURITYADMIN);
    private static final Set<String> MANAGERS = Set.of(DATAMANAGER, SECURITYADMIN);

    private final Map<String, DocumentDefinition> documents = catalogue();

    public List<DocumentSummary> visibleCatalogue(Authentication authentication) {
        Set<String> roles = roles(authentication);
        return documents.values().stream()
                .filter(document -> document.roles().stream().anyMatch(roles::contains))
                .map(DocumentDefinition::summary)
                .toList();
    }

    public SecuredDocument resolve(String documentId, Authentication authentication) {
        if (documentId == null || !documentId.matches("[a-z0-9-]{2,80}")) throw new ResponseStatusException(NOT_FOUND);
        DocumentDefinition definition = documents.get(documentId);
        Set<String> roles = roles(authentication);
        if (definition == null || definition.roles().stream().noneMatch(roles::contains)) {
            throw new ResponseStatusException(NOT_FOUND);
        }
        Resource resource = new ClassPathResource("secure-docs/" + definition.fileName());
        if (!resource.exists() || !resource.isReadable()) throw new ResponseStatusException(NOT_FOUND);
        return new SecuredDocument(definition.summary(), resource);
    }

    Set<String> roles(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return Set.of();
        Set<String> resolved = new LinkedHashSet<>();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String role = authority.getAuthority().trim().toUpperCase(Locale.ROOT).replaceFirst("^ROLE_", "").replaceAll("[^A-Z]", "");
            if ("ADMIN".equals(role)) role = DATAMANAGER;
            if (ALL.contains(role)) resolved.add(role);
        }
        return Set.copyOf(resolved);
    }

    private static Map<String, DocumentDefinition> catalogue() {
        Map<String, DocumentDefinition> values = new LinkedHashMap<>();
        add(values, "data-manager-manual", "Data Manager Operations Manual", "Role Manual", "Governance approvals, registries, mappings, document services and audit review.", List.of("Approvals", "Governance", "Audit"), Set.of(DATAMANAGER));
        add(values, "developer-manual", "Developer User Manual", "Role Manual", "Safe query authoring, dry runs, parameter contracts and submission.", List.of("SQL templates", "Dry run", "Submission"), Set.of(DEVELOPER, DATAMANAGER));
        add(values, "data-viewer-manual", "Data Viewer User Manual", "Role Manual", "Approved report discovery, parameters, results and responsible export.", List.of("Reports", "Filters", "Export"), Set.of(DATAVIEWER, DATAMANAGER));
        add(values, "external-user-manual", "External User Manual", "Role Manual", "Data request submission, review tracking and approved consumption.", List.of("Requests", "Status", "Reports"), Set.of(EXTERNAL, DATAMANAGER));
        add(values, "api-user-sdk-manual", "API User and SDK Manual", "Role Manual", "Credentials, TLS, certificate registration, AES, checksums and automation.", List.of("API key", "SDK", "AES"), Set.of(APIUSER, DATAMANAGER));
        add(values, "security-admin-manual", "Security Administrator Manual", "Role Manual", "Privileged identity lifecycle, recovery controls and oversight.", List.of("Privileged access", "Accounts", "Recovery"), MANAGERS);
        add(values, "password-policy-mfa", "DOORS Password Policy and MFA Guide", "Security", "Password composition, protected storage, forced change, TOTP enrollment, privileged access checks and governed recovery.", List.of("Password policy", "MFA", "TOTP", "Authentication", "Session security"), ALL);
        add(values, "security-architecture-controls", "Security Architecture and Controls", "Security", "Trust boundaries, TLS, hybrid encryption, signatures, audit and threats.", List.of("Zero trust", "Cryptography", "Audit"), MANAGERS);
        add(values, "rbac-segregation-duties", "RBAC and Segregation of Duties", "Governance", "Permissions, approval boundaries, conflicts and access reviews.", List.of("RBAC", "SoD", "Access review"), MANAGERS);
        add(values, "document-download-services-sdk", "Document Download Services and SDK", "Integration", "Registry, policy, streaming, audit, checksum receipts and encryption.", List.of("Document Hub", "Checksums", "Download logs"), Set.of(DATAMANAGER, APIUSER));
        add(values, "hosting-deployment-operations", "Hosting, Deployment and Operations Manual", "Operations", "Topology, deployment, configuration, monitoring, backup and recovery.", List.of("Hosting", "Monitoring", "DR"), MANAGERS);
        add(values, "test-catalogue-traceability", "Test Catalogue and Traceability Matrix", "Assurance", "Seventy-two functional, security, resilience and acceptance tests.", List.of("Test cases", "Evidence", "Traceability"), MANAGERS);
        add(values, "troubleshooting-runbooks", "Troubleshooting and Operational Runbooks", "Operations", "Symptom-led diagnosis, safe recovery and escalation.", List.of("Incidents", "Recovery", "Escalation"), Set.of(DATAMANAGER, DEVELOPER, SECURITYADMIN));
        add(values, "api-configuration-data-reference", "API, Configuration and Data Reference", "Technical", "API groups, configuration principles, migrations and error semantics.", List.of("API", "Configuration", "Schema"), Set.of(DATAMANAGER, DEVELOPER, APIUSER));
        add(values, "release-notes-known-issues", "Release Notes, Known Issues and Change Log", "Reference", "Capabilities, changes, limitations and upgrade considerations.", List.of("Versions", "Changes", "Limitations"), ALL);
        add(values, "faq-glossary-support", "FAQ, Glossary and Support Guide", "Reference", "Common questions, terminology and escalation-ready diagnostics.", List.of("FAQ", "Glossary", "Support"), ALL);
        add(values, "doors-process-flow-flyer", "DOORS Process Flow Flyer", "Flyer", "A visual guide to the governed journey from data request and approval through secure delivery.", List.of("Process flow", "Approvals", "Delivery"), ALL);
        add(values, "doors-rbac-security-flyer", "DOORS RBAC and Security Flyer", "Flyer", "A concise overview of platform roles, separation of duties and layered security controls.", List.of("RBAC", "Security", "Roles"), ALL);
        add(values, "doors-promotional-flyer", "DOORS Platform Overview Flyer", "Flyer", "An at-a-glance introduction to secure, governed and on-demand data sharing with DOORS.", List.of("Platform overview", "Governance", "Data sharing"), ALL);
        add(values, "end-to-end-encryption-process", "End-to-End Encryption and Decryption Process", "Technical", "Machine/API-client request protection, gateway verification, response encryption and Client-side validation.", List.of("RSA", "AES", "Digital signatures"), Set.of(DATAMANAGER, DEVELOPER, APIUSER, SECURITYADMIN), "2.0");
        add(values, "ui-backend-encryption-process", "UI-to-Master API Encryption and Decryption Process", "Technical", "Cookie-authenticated browser response protection using the scoped session cryptographic context.", List.of("HttpOnly session", "AES", "Browser security"), Set.of(DATAMANAGER, DEVELOPER, SECURITYADMIN), "2.0");
        add(values, "aira-architecture-operations", "AIra Architecture and Operations Guide", "Technical", "Local AI architecture, governed prompt routing, PDF and vector storage boundaries, Ollama integration, operational metrics, security and administration.", List.of("AIra", "Vector knowledge base", "PDF storage", "PostgreSQL", "Ollama", "RAG", "Operational metrics"), Set.of(DATAMANAGER, DEVELOPER, SECURITYADMIN), "1.2");
        add(values, "platform-reference", "Legacy DOORS Platform Reference Manual", "Legacy", "Original June 2026 consolidated handoff retained for historical reference.", List.of("Architecture", "Operations", "Deployment"), Set.of(DATAMANAGER));
        return Map.copyOf(values);
    }

    private static void add(Map<String, DocumentDefinition> target, String id, String title, String category,
                            String description, List<String> topics, Set<String> roles) {
        add(target, id, title, category, description, topics, roles, "1.0");
    }

    private static void add(Map<String, DocumentDefinition> target, String id, String title, String category,
                            String description, List<String> topics, Set<String> roles, String version) {
        target.put(id, new DocumentDefinition(id, title, category, description, topics, roles, id + ".pdf", version));
    }

    private record DocumentDefinition(String id, String title, String category, String description,
                                      List<String> topics, Set<String> roles, String fileName, String version) {
        DocumentSummary summary() {
            List<String> audiences = new ArrayList<>();
            if (roles.contains(DATAMANAGER)) audiences.add("Data Manager");
            if (roles.contains(DEVELOPER)) audiences.add("Developer");
            if (roles.contains(DATAVIEWER)) audiences.add("Data Viewer");
            if (roles.contains(EXTERNAL)) audiences.add("External User");
            if (roles.contains(APIUSER)) audiences.add("API User");
            if (roles.contains(SECURITYADMIN)) audiences.add("Security Administrator");
            return new DocumentSummary(id, title, category, description, topics, List.copyOf(audiences),
                    "Available", version, "/api/v1/documentation/documents/" + id);
        }
    }

    public record DocumentSummary(String id, String title, String category, String description,
                                  List<String> topics, List<String> audiences, String status,
                                  String version, String endpoint) { }
    public record SecuredDocument(DocumentSummary summary, Resource resource) { }
}
