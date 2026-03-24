package org.gepnic.doors.masterapi.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "data_pull_requests")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExternalRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "request_id")
    private Long id;

    @Column(name = "requested_by")
    private String requestedBy;

    @Column(name = "target_agent_id")
    private String targetAgentId;

    @Column(name = "justification", columnDefinition = "TEXT")
    private String justification;

    @Column(name = "sample_json", columnDefinition = "TEXT")
    private String sampleJson;

    @Column(name = "status")
    private String status;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "completion_time_ms")
    private Long completionTimeMs;

    @Column(name = "query_id")
    private Long queryId;

    // FIX: Remove @Lob if it causes issues with Postgres bytea
    // Explicitly map the bytea column as the last items to avoid index mismatch
    @Column(name = "attachment_data")
    private byte[] attachmentData;

    @Column(name = "attachment_name")
    private String attachmentName;

    @Column(name = "attachment_type")
    private String attachmentType;

    @Column(name = "request_title")
    private String requestTitle;
}