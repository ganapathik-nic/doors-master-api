package org.gepnic.doors.masterapi.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.Arrays;      
import java.util.Collections; 
import java.util.List;

@Data
@Entity
@Table(name = "external_api_clients")
public class ApiClient {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "client_id")
    private Long clientId;

    @Column(name = "client_name", nullable = false)
    private String clientName;

    @Column(name = "api_key", nullable = false, unique = true)
    private String apiKey;

    private String description;

    @Column(name = "allowed_ips") 
    private String allowedIps; 

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "is_encryption_enabled")
    private boolean isEncryptionEnabled = true;

    @Column(name = "client_public_key", columnDefinition = "TEXT")
    private String clientPublicKey;

    /**
     * Virtual field for the Frontend. 
     * Converts the DB String "1.1.1.1, 2.2.2.2" into a List for Vue Tags.
     */
    @Transient 
    public List<String> getIpWhitelist() {
        if (this.allowedIps == null || this.allowedIps.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.asList(this.allowedIps.split("\\s*,\\s*"));
    }

    public void setIpWhitelist(List<String> ips) {
        if (ips == null || ips.isEmpty()) {
            this.allowedIps = null;
        } else {
            this.allowedIps = String.join(",", ips);
        }
    }

    // 🚀 Manual Explicit Mappings to ensure perfect compilation bindings
    public String getClientPublicKey() {
        return this.clientPublicKey;
    }

    public void setClientPublicKey(String clientPublicKey) {
        this.clientPublicKey = clientPublicKey;
    }
}