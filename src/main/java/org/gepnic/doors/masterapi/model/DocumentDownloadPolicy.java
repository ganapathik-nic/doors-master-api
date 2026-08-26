package org.gepnic.doors.masterapi.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "document_download_policies")
@Getter
@Setter
@NoArgsConstructor
public class DocumentDownloadPolicy extends DoorsBaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "policy_id")
    private Long policyId;

    @Column(name = "policy_code", nullable = false, unique = true, length = 100)
    private String policyCode;

    @Column(name = "policy_name", nullable = false, length = 200)
    private String policyName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "execution_mode", nullable = false, length = 20)
    private String executionMode;

    @Column(name = "function_name", length = 300)
    private String functionName;

    @Column(name = "eligibility_sql", columnDefinition = "TEXT")
    private String eligibilitySql;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "accepted_identifiers", nullable = false, columnDefinition = "jsonb")
    private JsonNode acceptedIdentifiers;

    @Column(name = "decision_column", nullable = false, length = 100)
    private String decisionColumn = "decision";

    @Column(name = "allowed_value", nullable = false, length = 100)
    private String allowedValue = "ALLOW";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "document_types", nullable = false, columnDefinition = "jsonb")
    private JsonNode documentTypes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "authorized_client_ids", nullable = false, columnDefinition = "jsonb")
    private JsonNode authorizedClientIds;

    @Column(nullable = false, length = 20)
    private String status = "DRAFT";

    @Column(name = "policy_version", nullable = false)
    private Integer policyVersion = 1;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion = 0L;
}
