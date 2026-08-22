package org.gepnic.doors.masterapi.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "external_client_query_map")
public class ClientQueryMap {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long mapId;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "query_id", nullable = false)
    private Long queryId; // Correctly maps to public.sql_templates(query_id)

    @Column(name = "assigned_by")
    private String assignedBy;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt = LocalDateTime.now();

    @Column(name = "response_filter_column")
    private String responseFilterColumn;

    @Column(name = "response_filter_value")
    private String responseFilterValue;
}
