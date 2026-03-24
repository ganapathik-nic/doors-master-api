package org.gepnic.doors.masterapi.service;

import org.gepnic.doors.masterapi.model.SqlTemplate;
import org.gepnic.doors.masterapi.repository.SqlTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class QueryApprovalService {

    private final SqlTemplateRepository sqlTemplateRepository;

    public QueryApprovalService(SqlTemplateRepository sqlTemplateRepository) {
        this.sqlTemplateRepository = sqlTemplateRepository;
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
     * Approver Flow: Admin rejects the query with a reason.
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