package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.dto.MappingRequest;
import org.gepnic.doors.masterapi.model.SqlTemplate;
import org.gepnic.doors.masterapi.repository.DataPullRequestRepository;
import org.gepnic.doors.masterapi.repository.ClientQueryMapRepository;
import org.gepnic.doors.masterapi.repository.SqlTemplateRepository;
import org.gepnic.doors.masterapi.repository.AgentRepository;
import org.gepnic.doors.masterapi.service.AgentExecutionService;
import org.gepnic.doors.masterapi.service.MappingService;
import org.gepnic.doors.masterapi.service.TemplateService;
import org.gepnic.doors.masterapi.util.EncryptionUtils;
import org.gepnic.doors.masterapi.util.SqlSecurityValidator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.Authentication;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/v1/master/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final JdbcTemplate jdbcTemplate;
    private final TemplateService templateService;
    private final SqlTemplateRepository sqlTemplateRepository;
    private final MappingService mappingService;
    private final AgentExecutionService agentExecutionService;
    private final DataPullRequestRepository dataPullRequestRepository;
    private final ClientQueryMapRepository clientQueryMapRepository;
    private final AgentRepository agentRepository;
    private final org.gepnic.doors.masterapi.service.DataRequestAccess requestAccess;

    /**
     * 1. SUBMIT NEW PROPOSAL
     * Force sync via JDBC to ensure request linkage.
     */
    @Transactional
  @PostMapping("/submit")
public ResponseEntity<ApiResponse<Object>> submitTemplate(
        @jakarta.validation.Valid @RequestBody org.gepnic.doors.masterapi.dto.TemplateProposalRequest request,
        Authentication authentication) {
    SqlTemplate template = request.toNewEntity();
    if (authentication == null || !authentication.isAuthenticated()) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Authentication required", HttpStatus.UNAUTHORIZED.value()));
    }

    // Portal identity is authoritative; never trust a browser-supplied proposer ID.
    template.setProposerId(authentication.getName());
    template.setDefaultAgentId(normalizeOptionalText(template.getDefaultAgentId()));
    template.setCategory(normalizeOptionalText(template.getCategory()));
    template.setSubcategory(normalizeOptionalText(template.getSubcategory()));
    template.setDescription(normalizeOptionalText(template.getDescription()));
    boolean developer = hasAuthority(authentication, "DEVELOPER", "ROLE_DEVELOPER");
    if (developer) {
        if (!"REQUEST".equalsIgnoreCase(template.getSubmissionSource())
                || template.getRequestId() == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(
                    "Developers may submit SQL only for an approved data request.",
                    HttpStatus.FORBIDDEN.value()));
        }
        Integer approvedRequestCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM data_pull_requests WHERE request_id = ? AND UPPER(status) = 'APPROVED'",
                Integer.class,
                template.getRequestId());
        if (approvedRequestCount == null || approvedRequestCount == 0) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(
                    "The linked data request is not approved.",
                    HttpStatus.FORBIDDEN.value()));
        }
    }
    String submittedUniqueName = normalizeOptionalText(template.getUniqueName());
    if (submittedUniqueName == null || !submittedUniqueName.matches("[A-Za-z0-9][A-Za-z0-9 _-]{2,99}")) {
        return ResponseEntity.badRequest().body(ApiResponse.error(
                "Query Name must be 3-100 characters and contain only letters, numbers, spaces, hyphens or underscores.", 400));
    }
    if (sqlTemplateRepository.existsByUniqueNameIgnoreCase(submittedUniqueName)) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(
                "Query Name already exists. Enter a unique name.", HttpStatus.CONFLICT.value()));
    }
    template.setUniqueName(submittedUniqueName);
    try {
        // 🛡️ 1. DECRYPT: Turn the incoming AES gibberish back into a SQL string
        String encryptedSql = template.getSqlText();
        String decryptedSql = EncryptionUtils.decrypt(encryptedSql);
        
        // 🛡️ 2. VALIDATE: Check the decrypted plain text for security patterns
        if (!SqlSecurityValidator.isSafeSelectOnly(decryptedSql)) {
            log.warn("DOORS-SECURITY-ALERT: Unauthorized SQL pattern in submission!");
            return ResponseEntity.status(400).body(ApiResponse.error("Security Violation: SELECT output fields must use table data or plain-text labels; numeric and special-character fixed values are not allowed", 400));
        }

        // 🛡️ 3. UPDATE OBJECT: Set the decrypted SQL back into the template for storage
        template.setSqlText(decryptedSql);

    } catch (Exception e) {
        log.error("DOORS-AUTH-CRITICAL: SQL Decryption failed during submission: {}", e.getMessage());
        return ResponseEntity.status(400).body(ApiResponse.error("Invalid Request Payload: Decryption failed", 400));
    }

    // --- Standard logic continues with decrypted text ---
    template.setStatus("PENDING");
    template.setIsActive(true);
    template.setVersion(1);
    template.setCreatedAt(LocalDateTime.now());
    
    SqlTemplate saved = sqlTemplateRepository.save(template);
    
    if (saved.getRequestId() != null && "REQUEST".equalsIgnoreCase(saved.getSubmissionSource())) {
        try {
            jdbcTemplate.update(
                "UPDATE data_pull_requests SET status = ?, query_id = ? WHERE request_id = ?",
                "SQL-Submitted", saved.getQueryId(), saved.getRequestId()
            );
            log.info("DOORS-SYNC: Linked Q-{} to REQ-{}", saved.getQueryId(), saved.getRequestId());
        } catch (Exception e) {
            log.error("DOORS-SYNC-ERROR: {}", e.getMessage());
        }
    }
    return ResponseEntity.ok(ApiResponse.success((Object)saved, "Submitted successfully"));
}

    @GetMapping("/list/my-submissions")
public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMySubmissions(
        @RequestParam(required = false) String userId,
        @RequestParam(required = false) String role,
        Authentication authentication) {

    // Compatibility parameters are intentionally ignored. Identity and role
    // come only from the authenticated session.
    userId = authentication.getName();
    boolean manager = hasAuthority(authentication,
            "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN");
    role = manager ? "DataManager" : "Developer";
    
    log.info("DOORS-MASTER: Fetching SQL submissions for: {} (Role: {})", userId, role);
    try {
        String sql;
        // 🛡️ FIXED SQL: Removed 'title' (missing in DB) and used 'unique_name' as the title alias
        if ("DataManager".equalsIgnoreCase(role)) {
            sql = "SELECT query_id as \"id\", unique_name as \"uniqueName\", " +
                  "unique_name as \"title\", " + // 👈 Alias unique_name to title
                  "description, status, sql_text as \"sqlQuery\", created_at as \"createdAt\", " +
                  "proposer_id as \"proposerId\" " + 
                  "FROM sql_templates ORDER BY created_at DESC";
            return ResponseEntity.ok(ApiResponse.success(jdbcTemplate.queryForList(sql), "Global history retrieved"));
        } else {
            sql = "SELECT query_id as \"id\", unique_name as \"uniqueName\", " +
                  "unique_name as \"title\", " + // 👈 Alias unique_name to title
                  "description, status, sql_text as \"sqlQuery\", created_at as \"createdAt\" " +
                  "FROM sql_templates WHERE proposer_id = ? ORDER BY created_at DESC";
            return ResponseEntity.ok(ApiResponse.success(jdbcTemplate.queryForList(sql, userId), "Personal history retrieved"));
        }
    } catch (Exception e) {
        log.error("DOORS-DATABASE-ERROR: {}", e.getMessage());
        return ResponseEntity.status(500).body(ApiResponse.error("Database mismatch detected", 500));
    }
}
/* 
 @GetMapping("/list/my-submissions")
public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getMySubmissions(@RequestParam String userId) {
    log.info("DOORS-MASTER: Fetching SQL submissions for proposer: {}", userId);
    try {
        // Using 'sql_templates' table and 'proposer_id' column
        String sql = "SELECT query_id as \"id\", unique_name as \"uniqueName\", " +
                     "description, status, governance_note as \"governanceNote\", " +
                     "sql_text as \"sqlText\" " +
                     "FROM sql_templates WHERE proposer_id = ? ORDER BY created_at DESC";
        
        List<Map<String, Object>> submissions = jdbcTemplate.queryForList(sql, userId);
        return ResponseEntity.ok(ApiResponse.success(submissions, "Submissions retrieved"));
    } catch (Exception e) {
        log.error("DOORS-ERROR: Failed to fetch user submissions: {}", e.getMessage());
        return ResponseEntity.status(500).body(ApiResponse.error("Fetch failed: " + e.getMessage(), 500));
    }
}
    */

    /**
     * 2. UPDATE AUTHORIZED NODE MAPPING
     * Fixes the 404 "No static resource" error in Query Library.
     */
    @Transactional
    @PutMapping("/mapping/{queryId}")
    public ResponseEntity<ApiResponse<Object>> updateMapping(
            @PathVariable Long queryId, 
            @RequestBody MappingRequest request) {
        
        log.info("DOORS-MASTER: Updating node mapping for Q-{}", queryId);
        return sqlTemplateRepository.findById(queryId)
            .map(template -> {
                template.setAuthorizedAgents(request.getAgentIds());
                template.setUpdatedAt(LocalDateTime.now());
                sqlTemplateRepository.save(template);
                return ResponseEntity.ok(ApiResponse.success(null, "Mapping updated"));
            })
            .orElse(ResponseEntity.status(404).body(ApiResponse.error("Query not found", 404)));
    }

    /**
     * 3. APPROVAL & STATUS WORKFLOW
     * Finalizes the governance transition.
     */
    @Transactional
    @PutMapping("/status/{id}")
    public ResponseEntity<ApiResponse<Object>> approveAndMap(
            @PathVariable Long id, 
            @RequestBody Map<String, Object> payload, Authentication authentication) {
        
        return sqlTemplateRepository.findById(id).map(template -> {
            String newStatus = (String) payload.get("status");
            String editedSql = (String) payload.get("sqlText");
            if (!java.util.Set.of("PENDING", "APPROVED", "REJECTED", "DISABLED").contains(String.valueOf(newStatus)))
                throw new IllegalArgumentException("Unsupported template status");
            if ("APPROVED".equals(newStatus) && !"APPROVED".equals(template.getStatus())) {
                if (!"PENDING".equals(template.getStatus())) throw new IllegalStateException("Only pending templates can be approved");
                if (authentication.getName().equalsIgnoreCase(template.getProposerId()))
                    throw new SecurityException("A different reviewer must approve the proposal");
                if (editedSql != null && !editedSql.equals(template.getSqlText()))
                    throw new SecurityException("Submit SQL changes for review before approval");
                template.setApproverId(authentication.getName());
            }
            if (editedSql != null && !editedSql.equals(template.getSqlText())) {
                if (!"PENDING".equals(newStatus)) throw new SecurityException("SQL changes must return to pending review");
                template.setProposerId(authentication.getName());
                template.setApproverId(null);
            }

            if (payload.containsKey("uniqueName")) {
                String requestedUniqueName = normalizeOptionalText(payload.get("uniqueName"));
                if (requestedUniqueName == null) {
                    return ResponseEntity.badRequest().body(ApiResponse.error("Query Name is required.", 400));
                }
                if (!requestedUniqueName.equals(template.getUniqueName())) {
                    if (!requestedUniqueName.matches("[A-Za-z0-9][A-Za-z0-9 _-]{2,99}")) {
                        return ResponseEntity.badRequest().body(ApiResponse.error(
                                "New Query Name must be 3-100 characters and contain only letters, numbers, spaces, hyphens or underscores.", 400));
                    }
                    if (clientQueryMapRepository.existsByQueryId(id)) {
                        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(
                                "Query Name cannot be changed because this query is mapped to one or more API clients.", 409));
                    }
                    if (sqlTemplateRepository.existsByUniqueNameIgnoreCaseAndQueryIdNot(requestedUniqueName, id)) {
                        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(
                                "Query Name already exists. Enter a unique name.", 409));
                    }
                    String previousUniqueName = template.getUniqueName();
                    template.setUniqueName(requestedUniqueName);
                    log.warn("DOORS-GOVERNANCE: Query Name renamed for Q-{} from [{}] to [{}] after API-client mapping check.",
                            id, previousUniqueName, requestedUniqueName);
                }
            }

            // Status-only rejection/disablement must not revalidate historical SQL.
            // Approval always validates the stored SQL, even if no edit was sent.
            if (("APPROVED".equals(newStatus) ||
                    (editedSql != null && !editedSql.equals(template.getSqlText()))) &&
                    !SqlSecurityValidator.isSafeSelectOnly(
                            editedSql != null ? editedSql : template.getSqlText())) {
                return ResponseEntity.status(403).body(ApiResponse.error("Security Violation", 403));
            }

            template.setSqlText(editedSql != null ? editedSql : template.getSqlText());
            template.setStatus(newStatus);
            if (!"APPROVED".equals(newStatus)) template.setAuthorizedAgents(java.util.List.of());

            // Report Catalogue metadata is edited from the Query Library after
            // approval. Only overwrite a field when the request contains it so
            // ordinary status-only transitions preserve existing metadata.
            if (payload.containsKey("description")) {
                template.setDescription(normalizeOptionalText(payload.get("description")));
            }
            if (payload.containsKey("category")) {
                template.setCategory(normalizeOptionalText(payload.get("category")));
            }
            if (payload.containsKey("subcategory")) {
                template.setSubcategory(normalizeOptionalText(payload.get("subcategory")));
            }
            if (payload.containsKey("defaultAgentId")) {
                template.setDefaultAgentId(normalizeOptionalText(payload.get("defaultAgentId")));
            }
            template.setUpdatedAt(LocalDateTime.now());

            if ("APPROVED".equals(newStatus)) {
                @SuppressWarnings("unchecked")
                List<String> allowedAgents = (List<String>) payload.get("allowedAgents");
                if (allowedAgents != null) template.setAuthorizedAgents(allowedAgents);
                
                if (template.getRequestId() != null) {
                    dataPullRequestRepository.updateStatus(template.getRequestId(), "SQL-Approved");
                }
            }

            SqlTemplate updated = sqlTemplateRepository.save(template);
            return ResponseEntity.ok(ApiResponse.success((Object)updated, "Status: " + newStatus));
        }).orElse(ResponseEntity.status(404).body(ApiResponse.error("Not found", 404)));
    }

    private static String normalizeOptionalText(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static boolean hasAuthority(Authentication authentication, String... acceptedAuthorities) {
        if (authentication == null) {
            return false;
        }
        java.util.Set<String> accepted = java.util.Arrays.stream(acceptedAuthorities)
                .map(value -> value.toUpperCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        return authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority().toUpperCase(java.util.Locale.ROOT))
                .anyMatch(accepted::contains);
    }

    @GetMapping("/{id}/rename-eligibility")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRenameEligibility(@PathVariable Long id) {
        return sqlTemplateRepository.findById(id).map(template -> {
            boolean mappedToApiClient = clientQueryMapRepository.existsByQueryId(id);
            Map<String, Object> eligibility = new HashMap<>();
            eligibility.put("eligible", !mappedToApiClient);
            eligibility.put("mappedToApiClient", mappedToApiClient);
            eligibility.put("currentUniqueName", template.getUniqueName());
            eligibility.put("reason", mappedToApiClient
                    ? "This query is mapped to an API client. Remove all API-client mappings before renaming."
                    : "No API-client mapping exists. Query Name may be changed.");
            return ResponseEntity.ok(ApiResponse.success(eligibility, "Rename eligibility evaluated"));
        }).orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("Query not found", HttpStatus.NOT_FOUND.value())));
    }

    @GetMapping("/by-request/{requestId}")
    public ResponseEntity<ApiResponse<SqlTemplate>> getByRequest(@PathVariable Long requestId) {
        requestAccess.requireRead(requestId);
        return sqlTemplateRepository.findByRequestId(requestId)
            .map(t -> ResponseEntity.ok(ApiResponse.success(t, "Found")))
            .orElse(ResponseEntity.status(404).body(ApiResponse.error("Not Found", 404)));
    }
/**
     * ENHANCEMENT: Fetch details from the original Data Pull Request.
     * Used by the frontend to auto-populate justification and title.
     */
     @GetMapping("/requests/details/{requestId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRequestDetails(@PathVariable Long requestId) {
        requestAccess.requireRead(requestId);
        log.info("DOORS-MASTER: Fetching source request details for REQ-{}", requestId);
        try {
            // Updated SQL to match your actual schema:
            // 1. request_id (snake_case)
            // 2. request_title (snake_case)
            // 3. justification (exists)
            // 4. Note: category is missing from your table, so we return a default/null
            String sql = "SELECT request_id, request_title, justification FROM data_pull_requests WHERE request_id = ?";
            
            Map<String, Object> row = jdbcTemplate.queryForMap(sql, requestId);
            
            Map<String, Object> details = new HashMap<>();
            details.put("id", row.get("request_id"));
            details.put("title", row.get("request_title"));
            details.put("justification", row.get("justification"));
            details.put("category", ""); // Table currently lacks 'category' column
            
            return ResponseEntity.ok(ApiResponse.success(details, "Source request details loaded"));
        } catch (Exception e) {
            log.error("DOORS-ERROR: SQL Execution failed. Ensure request_id {} exists. Error: {}", requestId, e.getMessage());
            return ResponseEntity.status(404).body(ApiResponse.error("Request details not found in database", 404));
        }
    }
    @GetMapping("/list")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listTemplates(
            @RequestParam String status,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        try {
            String hybridKey = buildResponseHybridKey(authorizationHeader);
            List<SqlTemplate> templates = sqlTemplateRepository.findByStatus(status);
            String serializedTemplates = new com.fasterxml.jackson.databind.ObjectMapper()
                    .findAndRegisterModules()
                    .writeValueAsString(templates);

            Map<String, Object> encryptedEnvelope = new HashMap<>();
            encryptedEnvelope.put("isEncryptedPayload", true);
            encryptedEnvelope.put(
                    "secureData",
                    EncryptionUtils.encrypt(serializedTemplates, hybridKey)
            );
            encryptedEnvelope.put("rowCount", templates.size());

            return ResponseEntity.ok(ApiResponse.success(
                    encryptedEnvelope,
                    "Retrieved securely"
            ));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    ApiResponse.error(
                            exception.getMessage(),
                            HttpStatus.UNAUTHORIZED.value()
                    )
            );
        } catch (Exception exception) {
            log.error("Unable to encrypt SQL template list", exception);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    ApiResponse.error(
                            "Unable to secure the SQL template response",
                            HttpStatus.INTERNAL_SERVER_ERROR.value()
                    )
            );
        }
    }

    private String buildResponseHybridKey(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new IllegalArgumentException("Authorization token is required");
        }
        String rawJwt = authorizationHeader
                .replaceFirst("(?i)^Bearer\\s+", "")
                .trim();
        if (rawJwt.length() < 8) {
            throw new IllegalArgumentException("Authorization token is invalid");
        }
        return "D00RS-NI" + rawJwt.substring(rawJwt.length() - 8);
    }

    @GetMapping("/summary-counts")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getSummaryCounts() {
        List<Object[]> results = sqlTemplateRepository.countByStatusGrouped();
        Map<String, Long> counts = new HashMap<>();
        counts.put("APPROVED", 0L); counts.put("PENDING", 0L);
        counts.put("REJECTED", 0L); counts.put("DISABLED", 0L);
        for (Object[] r : results) { counts.put((String) r[0], (Long) r[1]); }
        return ResponseEntity.ok(ApiResponse.success(counts, "Counts retrieved"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SqlTemplate>> getTemplateById(@PathVariable Long id) {
        return sqlTemplateRepository.findById(id)
            .map(t -> ResponseEntity.ok(ApiResponse.success(t, "Loaded")))
            .orElse(ResponseEntity.status(404).body(ApiResponse.error("Not found", 404)));
    }

@GetMapping("/infrastructure/agents/list/active")
public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getActiveAgents() {
    log.info("DOORS-MASTER: Fetching active remote agents from registry");
    try {
        // Query only ACTIVE agents to filter out ARCHIVED one
        String sql = "SELECT agent_id as \"agentId\", display_name as \"displayName\" " +
                     "FROM agents WHERE is_active =true ORDER BY agent_id ASC";
        
        List<Map<String, Object>> agents = jdbcTemplate.queryForList(sql);
        return ResponseEntity.ok(ApiResponse.success(agents, "Active agents retrieved"));
    } catch (Exception e) {
        log.error("DOORS-ERROR: Failed to fetch agent registry: {}", e.getMessage());
        return ResponseEntity.status(500).body(ApiResponse.error("Registry lookup failed", 500));
    }
}
 @PostMapping("/test-dry-run")
public ResponseEntity<ApiResponse<Object>> testQuery(
        @RequestHeader(
                value = "Authorization",
                required = false
        ) String authorizationHeader,
        @RequestBody Map<String, Object> payload,
        Authentication authentication
) {
    try {
        // ---------------------------------------------------------------------
        // 1. Validate Authorization header and derive hybrid AES key
        // ---------------------------------------------------------------------

        if (authorizationHeader == null ||
                authorizationHeader.isBlank()) {

            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(
                            "Authorization token is missing",
                            HttpStatus.UNAUTHORIZED.value()
                    ));
        }

        if (!authorizationHeader.regionMatches(
                true,
                0,
                "Bearer ",
                0,
                7
        )) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(
                            "Invalid Authorization header",
                            HttpStatus.UNAUTHORIZED.value()
                    ));
        }

        String rawJwt = authorizationHeader
                .substring(7)
                .trim();

        if (rawJwt.length() < 8) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(
                            "Invalid authentication token",
                            HttpStatus.UNAUTHORIZED.value()
                    ));
        }

        /*
         * Must exactly match PendingQueries.vue:
         *
         * systemPart = D00RS-NI       -> 8 bytes
         * userPart   = JWT last 8     -> 8 bytes
         * hybridKey                  -> 16-byte AES-128 key
         */
        String systemPart = "D00RS-NI";

        String userPart = rawJwt.substring(
                rawJwt.length() - 8
        );

        String hybridKey = systemPart + userPart;

        if (hybridKey.getBytes(
                java.nio.charset.StandardCharsets.UTF_8
        ).length != 16) {
            log.error(
                    "DOORS-DRYRUN: Invalid hybrid-key length"
            );

            return ResponseEntity.status(
                            HttpStatus.INTERNAL_SERVER_ERROR
                    )
                    .body(ApiResponse.error(
                            "Unable to construct the encryption key",
                            HttpStatus.INTERNAL_SERVER_ERROR.value()
                    ));
        }

        // ---------------------------------------------------------------------
        // 2. Extract and validate request fields
        // ---------------------------------------------------------------------

        Object agentIdValue = payload.get("agentId");
        Object encryptedSqlValue = payload.get("encryptedSql");

        if (!(agentIdValue instanceof String) ||
                ((String) agentIdValue).isBlank()) {

            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(
                            "Please select a target Agent node",
                            HttpStatus.BAD_REQUEST.value()
                    ));
        }

        if (!(encryptedSqlValue instanceof String) ||
                ((String) encryptedSqlValue).isBlank()) {

            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(
                            "Encrypted SQL payload cannot be empty",
                            HttpStatus.BAD_REQUEST.value()
                    ));
        }

        String agentId =
                ((String) agentIdValue).trim();

        boolean developer = authentication != null
                && authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority().toUpperCase(java.util.Locale.ROOT))
                .anyMatch(authority -> authority.equals("DEVELOPER")
                        || authority.equals("ROLE_DEVELOPER"));
        if (developer) {
            boolean activeSandbox = agentRepository.findById(agentId)
                    .filter(agent -> Boolean.TRUE.equals(agent.getIsActive()))
                    .filter(agent -> Boolean.TRUE.equals(agent.getIsSandbox()))
                    .isPresent();
            if (!activeSandbox) {
                log.warn("DOORS-SECURITY: Developer [{}] attempted dry-run on non-sandbox agent [{}]",
                        authentication.getName(), agentId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(ApiResponse.error(
                                "Developers may execute dry-runs only on active Sandbox agents",
                                HttpStatus.FORBIDDEN.value()));
            }
        }

        String encryptedSql =
                ((String) encryptedSqlValue).trim();

        Map<String, Object> params = new HashMap<>();

        Object paramsValue = payload.get("params");

        if (paramsValue instanceof Map<?, ?> suppliedParams) {
            for (Map.Entry<?, ?> entry :
                    suppliedParams.entrySet()) {

                if (entry.getKey() != null) {
                    params.put(
                            String.valueOf(entry.getKey()),
                            entry.getValue()
                    );
                }
            }
        }

        // ---------------------------------------------------------------------
        // 3. Decrypt SQL using the hybrid key
        // ---------------------------------------------------------------------

        final String processedSql;

        try {
            processedSql = EncryptionUtils.decrypt(
                    encryptedSql,
                    hybridKey
            ).trim();
        } catch (Exception decryptionException) {
            /*
             * Do not fall back to treating encryptedSql as plaintext.
             * A decryption failure must stop the request.
             */
            log.warn(
                    "DOORS-DRYRUN: SQL decryption failed for agent {}: {}",
                    agentId,
                    decryptionException.getMessage()
            );

            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(
                            "Unable to decrypt the SQL payload. " +
                                    "The UI and backend encryption keys " +
                                    "may not match.",
                            HttpStatus.BAD_REQUEST.value()
                    ));
        }

        if (processedSql.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(
                            "Decrypted SQL query is empty",
                            HttpStatus.BAD_REQUEST.value()
                    ));
        }

        // ---------------------------------------------------------------------
        // 4. Reject invalid control characters
        // ---------------------------------------------------------------------

        for (int index = 0;
             index < processedSql.length();
             index++) {

            char character =
                    processedSql.charAt(index);

            if (Character.isISOControl(character) &&
                    character != '\n' &&
                    character != '\r' &&
                    character != '\t') {

                log.warn(
                        "DOORS-DRYRUN: Invalid control character " +
                                "detected in decrypted SQL"
                );

                return ResponseEntity.badRequest()
                        .body(ApiResponse.error(
                                "Decrypted SQL contains invalid characters",
                                HttpStatus.BAD_REQUEST.value()
                        ));
            }
        }

        // ---------------------------------------------------------------------
        // 5. Validate SELECT-only SQL
        // ---------------------------------------------------------------------

        if (!SqlSecurityValidator.isSafeSelectOnly(
                processedSql
        )) {
            log.warn(
                    "DOORS-SECURITY: Rejected unsafe dry-run SQL " +
                            "for agent {}",
                    agentId
            );

            throw new SecurityException(
                    "SELECT output fields must use table data or plain-text labels; numeric and special-character fixed values are not allowed"
            );
        }

        log.info(
                "DOORS-DRYRUN: Executing validated SQL on agent {}",
                agentId
        );

        // ---------------------------------------------------------------------
        // 6. Execute plaintext SQL through the trusted backend service
        // ---------------------------------------------------------------------

        Map<String, Object> executionResult =
                agentExecutionService.executeDryRun(
                        agentId,
                        processedSql,
                        params
                );

        if (executionResult == null) {
            executionResult = new HashMap<>();
        }

        // ---------------------------------------------------------------------
        // 7. Extract only the result data grid
        // ---------------------------------------------------------------------

        Object targetDataGrid;

        if (executionResult.containsKey("data")) {
            Object primaryData =
                    executionResult.get("data");

            if (primaryData instanceof Map<?, ?> primaryMap &&
                    primaryMap.containsKey("data")) {

                targetDataGrid =
                        primaryMap.get("data");
            } else {
                targetDataGrid = primaryData;
            }
        } else {
            /*
             * Preserve a valid empty result instead of returning
             * an unencrypted response.
             */
            targetDataGrid = executionResult;
        }

        if (targetDataGrid == null) {
            targetDataGrid =
                    java.util.Collections.emptyList();
        }

        // ---------------------------------------------------------------------
        // 8. Serialize and encrypt response with the same hybrid key
        // ---------------------------------------------------------------------

        String serializedResult;

        try {
            serializedResult =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsString(
                                    targetDataGrid
                            );
        } catch (Exception serializationException) {
            log.error(
                    "DOORS-DRYRUN: Result serialization failed",
                    serializationException
            );

            return ResponseEntity.status(
                            HttpStatus.INTERNAL_SERVER_ERROR
                    )
                    .body(ApiResponse.error(
                            "Failed to serialize the dry-run result",
                            HttpStatus.INTERNAL_SERVER_ERROR.value()
                    ));
        }

        final String securedCiphertext;

        try {
            securedCiphertext =
                    EncryptionUtils.encrypt(
                            serializedResult,
                            hybridKey
                    );
        } catch (Exception encryptionException) {
            log.error(
                    "DOORS-DRYRUN: Response encryption failed",
                    encryptionException
            );

            return ResponseEntity.status(
                            HttpStatus.INTERNAL_SERVER_ERROR
                    )
                    .body(ApiResponse.error(
                            "Failed to encrypt the dry-run result",
                            HttpStatus.INTERNAL_SERVER_ERROR.value()
                    ));
        }

        // ---------------------------------------------------------------------
        // 9. Build encrypted response envelope
        // ---------------------------------------------------------------------

        Map<String, Object> secureEnvelope =
                new HashMap<>();

        secureEnvelope.put(
                "isEncryptedPayload",
                true
        );

        secureEnvelope.put(
                "secureData",
                securedCiphertext
        );

        /*
         * rowCount is metadata only. The actual result rows remain
         * encrypted inside secureData.
         */
        if (executionResult.containsKey("rowCount")) {
            secureEnvelope.put(
                    "rowCount",
                    executionResult.get("rowCount")
            );
        }

        log.info(
                "DOORS-DRYRUN: Secure dry-run completed on agent {}",
                agentId
        );

        return ResponseEntity.ok(
                ApiResponse.success(
                        (Object) secureEnvelope,
                        "Dry-run completed successfully"
                )
        );

    } catch (SecurityException securityException) {
        log.warn(
                "DOORS-SECURITY: Dry-run rejected: {}",
                securityException.getMessage()
        );

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(
                        "Security Violation: " +
                                securityException.getMessage(),
                        HttpStatus.FORBIDDEN.value()
                ));

    } catch (
            org.springframework.jdbc.BadSqlGrammarException
                    sqlException
    ) {
        log.warn(
                "DOORS-DRYRUN: SQL syntax error: {}",
                sqlException.getMostSpecificCause()
                        .getMessage()
        );

        return ResponseEntity.badRequest()
                .body(ApiResponse.error(
                        "SQL Syntax Error: " +
                                sqlException
                                        .getMostSpecificCause()
                                        .getMessage(),
                        HttpStatus.BAD_REQUEST.value()
                ));

    } catch (IllegalStateException executionException) {
        log.warn(
                "DOORS-DRYRUN: Agent execution failed: {}",
                executionException.getMessage()
        );

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.error(
                        executionException.getMessage(),
                        HttpStatus.BAD_GATEWAY.value()
                ));

    } catch (IllegalArgumentException argumentException) {
        log.warn(
                "DOORS-DRYRUN: Invalid request: {}",
                argumentException.getMessage()
        );

        return ResponseEntity.badRequest()
                .body(ApiResponse.error(
                        argumentException.getMessage(),
                        HttpStatus.BAD_REQUEST.value()
                ));

    } catch (Exception exception) {
        log.error(
                "DOORS-DRYRUN: Unexpected execution failure",
                exception
        );

        return ResponseEntity.status(
                        HttpStatus.INTERNAL_SERVER_ERROR
                )
                .body(ApiResponse.error(
                        "Dry-run execution failed",
                        HttpStatus.INTERNAL_SERVER_ERROR.value()
                ));
    }
}
}
