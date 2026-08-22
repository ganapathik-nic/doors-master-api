package org.gepnic.doors.masterapi.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * MASTER: Category Registry
 * Used to provide dynamic categories for the 50-member team's query library.
 */
@Entity
@Table(name = "query_categories")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 20)
    private String code; // e.g., "MIS", "AUDIT"

    @Column(nullable = false, length = 100)
    private String name; // e.g., "Management Information System", "Security Audit"
     @Column(name = "parent_id")
    private Long parentId;
}