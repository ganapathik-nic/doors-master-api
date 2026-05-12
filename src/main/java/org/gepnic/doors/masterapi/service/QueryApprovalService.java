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
                 "requestid as \"id\", " +             
                 "request_title as \"requestTitle\", " + 
                 "target_agent_id as \"targetAgentId\", " + 
                 "requested_by as \"requestedBy\", " +   
                 "justification, " +
                 "samplejson as \"sampleJson\", " +      
                 "attachmentname as \"attachmentName\", " + 
                 "updated_at as \"approvedAt\" " +         
                 "FROM data_pull_requests " +
                 "WHERE status = 'APPROVED' " +
                 "ORDER BY createdat DESC";
    
    return jdbcTemplate.queryForList(sql);
}
    /**
     * Proposer Flow: Submit a new SQL query for review.
     */
    @Transactional
    public SqlTemplate proposeQuery(SqlTemplate template, String proposerId) {
        template.setStatus("PENDING");
        template.setProposerId(proposerId);
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