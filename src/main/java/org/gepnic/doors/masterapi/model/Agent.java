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

    // --- New Hybrid Model Specifications ---
    @Builder.Default  // Fixed: Guarantees builder instances preserve default assignment state
    @Column(name = "agent_type", nullable = false)
    private String agentType = "INDIVIDUAL"; // 'CENTRAL' or 'INDIVIDUAL'

    @Column(name = "target_db_host")
    private String targetDbHost;

    @Column(name = "target_db_port")
    private Integer targetDbPort;

    @Column(name = "target_db_name")
    private String targetDbName;

    @Column(name = "target_db_user")
    private String targetDbUser;

    @Column(name = "target_db_password")
    private String targetDbPassword;

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
    @Column(name = "status")
    private String status = "ACTIVE";

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        normalizeBaseUrl();
        
        // Null protection checks for database column constraints
        if (this.agentType == null) this.agentType = "INDIVIDUAL"; 
        if (this.isActive == null) this.isActive = true;
        if (this.isSandbox == null) this.isSandbox = false; 
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