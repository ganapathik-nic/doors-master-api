package org.gepnic.doors.masterapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "document_service_registry")
@Getter
@Setter
@NoArgsConstructor
public class DocumentServiceRegistration extends DoorsBaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "service_id")
    private Long serviceId;

    @Column(name = "agent_id", nullable = false, unique = true, length = 255)
    private String agentId;

    @Column(name = "service_name", nullable = false, length = 200)
    private String serviceName;

    @Column(name = "base_url", nullable = false, length = 1000)
    private String baseUrl;

    @Column(name = "download_path", nullable = false, length = 500)
    private String downloadPath = "/Documents/downloadDocuments";

    @Column(name = "manifest_query_name", length = 100)
    private String manifestQueryName;

    @Column(name = "manifest_client_name", length = 255)
    private String manifestClientName;

    @Column(name = "document_download_policy_code", length = 100)
    private String documentDownloadPolicyCode;

    @Column(name = "access_mode", nullable = false, length = 30)
    private String accessMode = "MASTER_DIRECT";

    @Column(name = "connect_timeout_ms", nullable = false)
    private Integer connectTimeoutMs = 10000;

    @Column(name = "read_timeout_ms", nullable = false)
    private Integer readTimeoutMs = 120000;

    @Column(name = "verify_tls", nullable = false)
    private Boolean verifyTls = true;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion = 0L;
}
