package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final JdbcTemplate jdbcTemplate;

    // Propagation.REQUIRES_NEW ensures the log is saved even if the main transaction rolls back
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAction(Long queryId, String action, String oldVal, String newVal, String user) {
        String sql = "INSERT INTO governance_audit_logs (query_id, action_type, old_value, new_value, performed_by) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, queryId, action, oldVal, newVal, user);
    }
}