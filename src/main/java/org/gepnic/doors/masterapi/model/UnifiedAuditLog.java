package org.gepnic.doors.masterapi.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "unified_audit_logs")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class UnifiedAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @Column(name = "event_type", nullable = false)
    private String eventType; // e.g., "API_QUERY", "SYSTEM_EVENT"

    @Column(name = "username")
    private String username;

    @Column(name = "query_id")
    private Integer queryId;

    @Column(name = "query_name")
    private String queryName;

    @Column(name = "endpoint")
    private String endpoint;

    @Column(name = "method")
    private String method; // GET, POST, etc.

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "client_ip")
    private String clientIp;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "execution_time", insertable = false, updatable = false)
    private LocalDateTime executionTime;
}