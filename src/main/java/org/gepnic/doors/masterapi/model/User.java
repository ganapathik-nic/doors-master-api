 package org.gepnic.doors.masterapi.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import org.gepnic.doors.masterapi.entity.ApiClient;

/**
 * Entity representing a Portal User.
 * Updated to support Matrix-based Agent Authorization.
 */
@Entity
@Table(name = "users")
@Data 
public class User {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id") 
    private Integer userId;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String role; // ADMIN, EXTERNAL

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_api_clients",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "client_id"))
    private Set<ApiClient> apiClients = new LinkedHashSet<>();

    @Column(name = "is_active")
    private Boolean isActive = false;

    @Column(name = "status", length = 20)
    private String status = "PENDING"; // PENDING, APPROVED, REJECTED

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "password_reset_required")
    private Boolean passwordResetRequired = true;

    // Governance & Profile Fields
    private String name;
    private String org;
    private String description;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * MATRIX AUTHORIZATION: Agent Mapping
     * Stores the RHEL Agents this specific user is allowed to access.
     * Intersection logic: (User Agents) ∩ (Query Agents) = Target Execution Nodes.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    
    @CollectionTable(
        name = "user_authorized_agents", 
        //joinColumns = @JoinColumn(name = "user_id", referencedColumnName = "username")
        joinColumns = @JoinColumn(name = "user_name", referencedColumnName = "username")
    )
    @Column(name = "agent_id")
    private List<String> assignedAgents = new ArrayList<>();
    @Column(name = "current_session_id")
private String currentSessionId;

    @Column(name = "mfa_enabled", nullable = false)
    private Boolean mfaEnabled = false;

    @Column(name = "mfa_secret_encrypted", columnDefinition = "TEXT")
    private String mfaSecretEncrypted;

    @Column(name = "vpn_ip", length = 1024)
    private String vpnIp;

    @Column(name = "vpn_certificate_reference")
    private String vpnCertificateReference;

    @Column(name = "vpn_status", length = 30, nullable = false)
    private String vpnStatus = "NOT_REQUIRED";

    @Column(name = "privileged_approved_by")
    private String privilegedApprovedBy;

    @Column(name = "privileged_approved_at")
    private LocalDateTime privilegedApprovedAt;
    // Helper method to ensure list is never null
    public List<String> getAssignedAgents() {
        if (this.assignedAgents == null) {
            this.assignedAgents = new ArrayList<>();
        }
        return this.assignedAgents;
    }
    public String getCurrentSessionId() {
    return currentSessionId;
}

public void setCurrentSessionId(String currentSessionId) {
    this.currentSessionId = currentSessionId;
}
}
