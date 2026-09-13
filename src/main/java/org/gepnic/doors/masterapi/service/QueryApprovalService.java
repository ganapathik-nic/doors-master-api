package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.model.SqlTemplate;
import org.gepnic.doors.masterapi.repository.SqlTemplateRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor // Automatically handles constructor injection for final fields
public class QueryApprovalService {

    private final SqlTemplateRepository sqlTemplateRepository;
    private final JdbcTemplate jdbcTemplate; // Required for raw SQL on data_pull_requests

    /**
     * Fetch assignments for Developers.
     * Selects approved data requests that need an SQL fulfillment.
     */
 public List<Map<String, Object>> getApprovedDataRequests() {
    // Wrap aliases in double quotes \" to force CamelCase keys in the Map
    String sql = "SELECT " +
                 "request_id as \"id\", " +
                 "request_title as \"requestTitle\", " + 
                 "target_agent_id as \"targetAgentId\", " + 
                 "requested_by as \"requestedBy\", " +   
                 "justification, " +
                 "sample_json as \"sampleJson\", " +
                 "attachment_name as \"attachmentName\", " +
                 "updated_at as \"approvedAt\" " +
                 "FROM data_pull_requests " +
                 "WHERE status = 'APPROVED' " +
                 "ORDER BY created_at DESC";
    
    return jdbcTemplate.queryForList(sql);
}
    /**
     * Proposer Flow: Submit a new SQL query for review.
     */
    @Transactional
    public SqlTemplate proposeQuery(SqlTemplate template, String proposerId) {
        if (template.getQueryId() != null) throw new SecurityException("Proposal cannot update an existing template");
        if (!org.gepnic.doors.masterapi.util.SqlSecurityValidator.isSafeSelectOnly(template.getSqlText()))
            throw new IllegalArgumentException("Only a single read-only SELECT is permitted");
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new SecurityException("Authentication required");
        if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equalsIgnoreCase("DEVELOPER")
                || a.getAuthority().equalsIgnoreCase("ROLE_DEVELOPER"))) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM data_pull_requests WHERE request_id = ? AND UPPER(status) = 'APPROVED'",
                    Integer.class, template.getRequestId());
            if (!"REQUEST".equalsIgnoreCase(template.getSubmissionSource()) || count == null || count != 1)
                throw new SecurityException("An approved data request is required");
        }
        if (template.getUniqueName() == null || !template.getUniqueName().matches("[A-Za-z0-9][A-Za-z0-9 _-]{2,99}"))
            throw new IllegalArgumentException("Invalid query name");
        if (sqlTemplateRepository.existsByUniqueNameIgnoreCase(template.getUniqueName()))
            throw new IllegalArgumentException("Query name already exists");
        template.setAuthorizedAgents(java.util.List.of());
        template.setApproverId(null);
        template.setStatus("PENDING");
        template.setProposerId(auth.getName());
        template.setVersion(1);
        return sqlTemplateRepository.save(template);
    }

    /**
     * Approver Flow: Admin reviews and approves the query.
     */
    @Transactional
    public SqlTemplate approveQuery(Long queryId, String adminId) {
        SqlTemplate template = sqlTemplateRepository.findById(queryId)
                .orElseThrow(() -> new RuntimeException("Query not found with ID: " + queryId));
        
        if (!"PENDING".equals(template.getStatus())) throw new IllegalStateException("Only pending templates can be approved");
        if (adminId.equalsIgnoreCase(template.getProposerId())) throw new SecurityException("A different reviewer must approve the proposal");
        if (!org.gepnic.doors.masterapi.util.SqlSecurityValidator.isSafeSelectOnly(template.getSqlText()))
            throw new IllegalArgumentException("Only a single read-only SELECT is permitted");
        template.setStatus("APPROVED");
        template.setApproverId(adminId);
        return sqlTemplateRepository.save(template);
    }

    /**
     * Admin rejects the query with a reason.
     */
    @Transactional
    public SqlTemplate rejectQuery(Long queryId, String adminId, String reason) {
        SqlTemplate template = sqlTemplateRepository.findById(queryId)
                .orElseThrow(() -> new RuntimeException("Query not found with ID: " + queryId));
        
        template.setStatus("REJECTED");
        template.setApproverId(adminId);
        template.setDescription(template.getDescription() + " | Rejection Reason: " + reason);
        return sqlTemplateRepository.save(template);
    }

    public List<SqlTemplate> getPendingQueries() {
        return sqlTemplateRepository.findByStatus("PENDING");
    }

    public List<SqlTemplate> getApprovedQueries() {
        return sqlTemplateRepository.findByStatus("APPROVED");
    }
}
