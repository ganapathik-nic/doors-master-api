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

    // 🚀 ALIGNED PROPERTY REFERENCE: Maps response_status / status_code safely
    @Column(name = "status_code")
    private Integer statusCode;

    // 🚀 ALIGNED PROPERTY REFERENCE: Maps execution_time_ms / duration_ms safely
    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "client_ip")
    private String clientIp;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "record_count")
    private Integer recordCount;

    // 🚀 THE CRITICAL ADDITION: Bridges the indexed PostgreSQL trace column to your entity map pipeline
    @Column(name = "trace_id", length = 50)
    private String traceId;

    @Column(name = "execution_time", insertable = false, updatable = false)
    private LocalDateTime executionTime;
}
