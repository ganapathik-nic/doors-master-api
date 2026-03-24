package org.gepnic.doors.masterapi.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Entity to manage third-party access tokens for the GEMS Gateway.
 * Allows the 50-member team to revoke access without code changes.
 */
@Entity
@Table(name = "api_keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
  
    @Column(name = "consumer_name", nullable = false)
    private String consumerName; // e.g., "Finance_Audit_System"

    @Column(name = "api_key", nullable = false, unique = true)
    private String apiKey; // The actual secret token

    @Column(name = "is_active")
      @Builder.Default
    private boolean isActive = true; 
   // private Boolean isActive = true;

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
    //private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}