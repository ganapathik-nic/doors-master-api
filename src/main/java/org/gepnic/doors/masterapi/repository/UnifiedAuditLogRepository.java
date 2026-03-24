package org.gepnic.doors.masterapi.repository;

import java.util.List;

import org.gepnic.doors.masterapi.model.UnifiedAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UnifiedAuditLogRepository extends JpaRepository<UnifiedAuditLog, Long> {
    List<UnifiedAuditLog> findByQueryId(Integer queryId);
    List<UnifiedAuditLog> findByUsername(String username);
}