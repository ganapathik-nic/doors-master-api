package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.gepnic.doors.masterapi.service.AuditService;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateService {

    private final JdbcTemplate jdbcTemplate;
private final AuditService auditService;
    /**
     * Updates the lifecycle status of a query (APPROVED, REJECTED, DISABLED, etc.)
     */
    @Transactional
    public void updateStatus(Long queryId, String newStatus) {
        // 1. Fetch current status for the "old_value" audit
    String currentStatus = jdbcTemplate.queryForObject(
        "SELECT status FROM sql_templates WHERE query_id = ?", String.class, queryId);

    // 2. Perform the update
    jdbcTemplate.update("UPDATE sql_templates SET status = ? WHERE query_id = ?", newStatus, queryId);

    // 3. LOG THE CHANGE
    auditService.logAction(queryId, "STATUS_CHANGE", currentStatus, newStatus, "ADMIN_USER");
        log.info("GOVERNANCE: Updating status for Query ID: {} to {}", queryId, newStatus);

        // 1. Update the main table
        String sql = "UPDATE sql_templates SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE query_id = ?";
        int rows = jdbcTemplate.update(sql, newStatus, queryId);

        if (rows == 0) {
            throw new RuntimeException("Template not found with ID: " + queryId);
        }

        // 2. Critical Governance Logic: 
        // If a query is REJECTED or DISABLED, automatically revoke all node authorizations
        if ("REJECTED".equalsIgnoreCase(newStatus) || "DISABLED".equalsIgnoreCase(newStatus)) {
            String cleanupSql = "DELETE FROM sql_template_authorized_agents WHERE query_id = ?";
            jdbcTemplate.update(cleanupSql, queryId);
            log.warn("GOVERNANCE: Revoked all node access for Query ID: {} due to status change", queryId);
        }
    }

    /**
     * Assigns infrastructure nodes to a query
     */
    @Transactional
    public void assignAgentsToQuery(Long queryId, List<String> agentIds) {
        // Clear existing
        jdbcTemplate.update("DELETE FROM sql_template_authorized_agents WHERE query_id = ?", queryId);

        // Batch Insert new ones
        if (agentIds != null && !agentIds.isEmpty()) {
            String insertSql = "INSERT INTO sql_template_authorized_agents (query_id, agent_id) VALUES (?, ?)";
            for (String agentId : agentIds) {
                jdbcTemplate.update(insertSql, queryId, agentId);
            }
        }
    }
}