package org.gepnic.doors.masterapi.repository;

import org.gepnic.doors.masterapi.model.ExternalRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;


@Repository
public interface DataPullRequestRepository extends JpaRepository<ExternalRequest, Long> {
    Optional<ExternalRequest> findByIdAndRequestedBy(Long id, String requestedBy);

    /**
     * Fetches all requests submitted by a specific user for their history view.
     */
    List<ExternalRequest> findByRequestedByOrderByCreatedAtDesc(String requestedBy);

    /**
     * Fetches requests based on their lifecycle status (SUBMITTED, APPROVED, etc.).
     */
    List<ExternalRequest> findByStatusOrderByCreatedAtDesc(String status);

    /**
     * Optimized query for the Data Manager list view.
     * Excludes the heavy 'attachmentData' (BLOB) to save memory during listing.
     */
  
 @Query("SELECT r.id, r.requestedBy, r.targetAgentId, r.status, r.createdAt, r.queryId, r.rejectionReason " +
       "FROM ExternalRequest r WHERE r.status = :status")
List<Object[]> findSummaryByStatus(@Param("status") String status);
  
 @Modifying(clearAutomatically = true, flushAutomatically = true)
@Transactional
@Query("UPDATE ExternalRequest r SET r.status = :status, r.queryId = :qId WHERE r.id = :id")
int updateStatusAndLinkQuery(@Param("id") Long id, @Param("status") String status, @Param("qId") Long qId);
 
@Modifying
@Query("UPDATE ExternalRequest r SET r.status = :status WHERE r.id = :id")
void updateStatus(@Param("id") Long id, @Param("status") String status);
}
