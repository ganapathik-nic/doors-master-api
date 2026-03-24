package org.gepnic.doors.masterapi.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * ENTITY: Agent Registry
 * Maps to the 'agents' table in PostgreSQL. 
 * Represents distributed nodes like Dev-01, NDCSP, and GEMS.
 */
@Entity
@Table(name = "agents")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Agent {
    @Id
    @Column(name = "agent_id")
    private String agentId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @Column(name = "agent_instance_code")
    private String agentInstanceCode;

    @Builder.Default
    @Column(name = "is_active")
    private Boolean isActive = true;

    // NEW FIELD: Used for Sandbox-only Dry Run logic
    @Builder.Default
    @Column(name = "is_sandbox")
    private Boolean isSandbox = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;

    @Builder.Default
    private String status = "ACTIVE";

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        normalizeBaseUrl();
        if (this.isActive == null) this.isActive = true;
        if (this.isSandbox == null) this.isSandbox = false; // Ensure no NULLs
        if (this.createdBy == null) this.createdBy = "SYSTEM";
        if (this.updatedBy == null) this.updatedBy = "SYSTEM"; 
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
        normalizeBaseUrl();
    }

    /**
     * Prevents common double-slash URL errors (e.g., http://host:8080//api/v1)
     */
    private void normalizeBaseUrl() {
        if (this.baseUrl != null && this.baseUrl.endsWith("/")) {
            this.baseUrl = this.baseUrl.substring(0, this.baseUrl.length() - 1);
        }
    }
}