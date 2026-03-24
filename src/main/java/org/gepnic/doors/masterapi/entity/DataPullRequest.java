package org.gepnic.doors.masterapi.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;

/**
 * Entity for tracking external data orchestration requests.
 * Files are stored directly in the database as binary data.
 */
@Entity
@Table(name = "data_pull_requests")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataPullRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "request_id") // Force mapping to request_id
    private Long requestId;
    @Column(name = "requested_by", nullable = false)
private String requestedBy;
    @Column(name = "target_agent_id", nullable = false)
    private String targetAgentId;
   
   

    @Column(columnDefinition = "TEXT", nullable = false)
    private String justification; // Business purpose

    @Column(name = "sample_json", columnDefinition = "TEXT")
private String sampleJson;
    @Column(name = "request_title", nullable = false, length = 150)
    
private String requestTitle;

    // Link to the SQL Template (Nullable until Data Manager maps it)
    @Column(name = "query_id")
    private Long queryId;

    @Column(nullable = false)
    private String status = "SUBMITTED";
   

    private String approvedBy;
    
     
     
    @Column(name = "rejection_reason") // This MUST match the DB column exactly
private String rejectionReason;
@Column(name = "created_at")
private LocalDateTime createdAt = LocalDateTime.now();
    // --- BLOB Storage Fields ---

    @Lob
    @Column(name = "attachment_data")
    @JdbcTypeCode(java.sql.Types.VARBINARY) // Forces Hibernate to use bytea instead of OID
    private byte[] attachmentData; // Stores the binary content of the PDF/Email

    private String attachmentName; // Original filename (e.g., "request_signed.pdf")

    private String attachmentType; // MIME type (e.g., "application/pdf")
    // Add this field to your DataPullRequest.java entity
     
 
}