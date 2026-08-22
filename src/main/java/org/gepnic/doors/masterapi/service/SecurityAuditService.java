package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class SecurityAuditService {
    private final JdbcTemplate jdbcTemplate;

    public void record(String actor, String action, String target, int statusCode) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO unified_audit_logs (event_type, username, query_name, endpoint, method, "
                            + "status_code, duration_ms, error_message, record_count, full_command, execution_time) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    "PRIVILEGED_ACCESS", actor, action, "/api/v1/admin/users", "POST",
                    statusCode, 0L, null, 0, "target=" + target);
        } catch (Exception exception) {
            log.error("Unable to persist privileged-access audit event [{}] for [{}]", action, target, exception);
        }
    }
}
