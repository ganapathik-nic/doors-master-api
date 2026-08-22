package org.gepnic.doors.masterapi.model;

import jakarta.persistence.*;
import lombok.*;
import java.util.List;
import java.util.ArrayList;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
 
 
/**
 * Entity representing a SQL Template in the DOORS system.
 * This drives the On-demand Reporting governance for MSR and Dashboard consumers.
 */
@Entity
@Table(name = "sql_templates")
@Getter 
@Setter
@NoArgsConstructor 
@AllArgsConstructor
public class SqlTemplate extends DoorsBaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "query_id")
    private Long queryId;

    @Column(name = "unique_name", unique = true, nullable = false, length = 100)
    private String uniqueName;

    @Column(name = "sql_text", nullable = false, columnDefinition = "TEXT")
    private String sqlText;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "category", length = 50)
    private String category; 

    @Column(name = "subcategory", length = 100)
    private String subcategory;

    @Column(name = "default_agent_id", length = 100)
    private String defaultAgentId;

    @Column(name = "status", length = 20)
    private String status = "PENDING"; 

    // This field maps to the 'proposer_id' column in your DB
    @Column(name = "proposer_id", length = 50)
    private String proposerId;

    @Column(name = "approver_id", length = 50)
    private String approverId;

    @Column(name = "version")
    private Integer version = 1;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "is_secure")
    private Boolean isSecure = true;

    @Column(name = "governance_note", columnDefinition = "TEXT")
    private String governanceNote;

    @Column(name = "last_tested_agent", length = 100)
    private String lastTestedAgent;

    @Column(name = "last_test_result", columnDefinition = "TEXT")
    private String lastTestResult;

    /**
     * Infrastructure Authorization
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "sql_template_authorized_agents", 
        joinColumns = @JoinColumn(name = "query_id")
    )
    @Column(name = "agent_id")
    private List<String> authorizedAgents = new ArrayList<>();

    public void setAuthorizedAgents(List<String> authorizedAgents) {
        this.authorizedAgents = authorizedAgents != null ? authorizedAgents : new ArrayList<>();
    }
    @Column(name = "request_id")
private Long requestId; // Nullable

@Column(name = "submission_source")
private String submissionSource; // "REQUEST" or "INTERNAL"
     /**
     * Captured SQL Placeholders (e.g., target_year, target_month)
     * Maps to PostgreSQL 'text[]' column.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "parameters", columnDefinition = "text[]")
    private List<String> parameters = new ArrayList<>();

    // HELPER: Ensures no null pointer issues during DTO mapping
    public List<String> getParameters() {
        return parameters != null ? parameters : new ArrayList<>();
    }

    public void setParameters(List<String> parameters) {
        this.parameters = parameters != null ? parameters : new ArrayList<>();
    }
}
