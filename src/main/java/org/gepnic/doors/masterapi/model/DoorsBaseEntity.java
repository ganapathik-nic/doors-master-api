package org.gepnic.doors.masterapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.*;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@MappedSuperclass
@EntityListeners(AuditingEntityListener.class) // Required to trigger the auto-save
@Getter @Setter
public abstract class DoorsBaseEntity {

    @CreatedBy
    @Column(name = "created_by", updatable = false, nullable = false)
    private String createdBy; // Set only once on creation

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @LastModifiedBy
    @Column(name = "updated_by", nullable = false)
    private String updatedBy; // Updated on every modification

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}