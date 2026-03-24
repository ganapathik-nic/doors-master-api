package org.gepnic.doors.masterapi.repository;

import org.gepnic.doors.masterapi.model.SqlTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SqlTemplateRepository extends JpaRepository<SqlTemplate, Long> {

    // 1. Sidebar Badges
    long countByCategory(String category);
    long countByStatus(String status);

    // 2. Main Library View
    List<SqlTemplate> findByStatusAndIsActive(String status, Boolean isActive);
    
    // 3. Admin Review Lists
    List<SqlTemplate> findByStatus(String status);

    // 4. Duplicate Check
    Optional<SqlTemplate> findByUniqueName(String uniqueName);

    // 5. FIXED: My Submissions Query
    // Uses 'proposerId' to match the Entity field and 'createdAt' from DoorsBaseEntity
    List<SqlTemplate> findByProposerIdOrderByCreatedAtDesc(String proposerId);

    // 6. Optimized Join for Orchestration
    @Query("SELECT t FROM SqlTemplate t LEFT JOIN FETCH t.authorizedAgents WHERE t.status = 'APPROVED'")
    List<SqlTemplate> findAllApprovedWithAgents();

    // 7. Dashboard Analytics
    @Query("SELECT t.status, COUNT(t) FROM SqlTemplate t GROUP BY t.status")
    List<Object[]> countByStatusGrouped();
    // Inside SqlTemplateRepository.java

    @Query("SELECT t FROM SqlTemplate t WHERE t.requestId = :rid")
    Optional<SqlTemplate> findByRequestId(@Param("rid") Long rid);
 
}