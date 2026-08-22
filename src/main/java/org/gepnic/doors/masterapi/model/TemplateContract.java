package org.gepnic.doors.masterapi.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "template_contracts", uniqueConstraints =
        @UniqueConstraint(name = "uq_template_contract_version", columnNames = {"template_id", "contract_version"}))
@Getter
@Setter
@NoArgsConstructor
public class TemplateContract extends DoorsBaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "contract_id")
    private Long contractId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_id", nullable = false)
    private SqlTemplate template;

    @Column(name = "contract_version", nullable = false)
    private Integer contractVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_schema", nullable = false, columnDefinition = "jsonb")
    private JsonNode requestSchema;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_schema", columnDefinition = "jsonb")
    private JsonNode responseSchema;

    @Column(name = "contract_status", nullable = false, length = 30)
    private String contractStatus;

    @Column(name = "discovery_source", nullable = false, length = 30)
    private String discoverySource;

    @Column(name = "schema_hash", length = 64)
    private String schemaHash;

    @Column(name = "change_type", length = 30)
    private String changeType;

    @Column(name = "observation_count", nullable = false)
    private Long observationCount = 0L;

    @Column(name = "first_observed_at")
    private LocalDateTime firstObservedAt;

    @Column(name = "last_observed_at")
    private LocalDateTime lastObservedAt;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "submitted_by", length = 100)
    private String submittedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "reviewed_by", length = 100)
    private String reviewedBy;

    @Column(name = "review_comment", columnDefinition = "TEXT")
    private String reviewComment;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "is_current", nullable = false)
    private Boolean isCurrent = false;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion = 0L;
}
