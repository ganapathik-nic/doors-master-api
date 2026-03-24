package org.gepnic.doors.masterapi.repository;

import org.gepnic.doors.masterapi.entity.DataPullRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;


@Repository
public interface DataPullRequestRepository extends JpaRepository<DataPullRequest, Long> {

    /**
     * Fetches all requests submitted by a specific user for their history view.
     */
    List<DataPullRequest> findByRequestedByOrderByCreatedAtDesc(String requestedBy);

    /**
     * Fetches requests based on their lifecycle status (SUBMITTED, APPROVED, etc.).
     */
    List<DataPullRequest> findByStatusOrderByCreatedAtDesc(String status);

    /**
     * Optimized query for the Data Manager list view.
     * Excludes the heavy 'attachmentData' (BLOB) to save memory during listing.
     */
  
 @Query("SELECT r.requestId, r.requestedBy, r.targetAgentId, r.status, r.createdAt, r.queryId, r.rejectionReason " +
       "FROM DataPullRequest r WHERE r.status = :status")
List<Object[]> findSummaryByStatus(@Param("status") String status);
  
 @Modifying(clearAutomatically = true, flushAutomatically = true)
@Transactional
@Query("UPDATE DataPullRequest r SET r.status = :status, r.queryId = :qId WHERE r.requestId = :id")
int updateStatusAndLinkQuery(@Param("id") Long id, @Param("status") String status, @Param("qId") Long qId);
 
@Modifying
@Query("UPDATE DataPullRequest r SET r.status = :status WHERE r.requestId = :id")
void updateStatus(@Param("id") Long id, @Param("status") String status);
}
 