package org.gepnic.doors.masterapi.repository;

import org.gepnic.doors.masterapi.model.ExternalRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
@Repository
public interface ExternalRequestRepository extends JpaRepository<ExternalRequest, Long> {

    // This defines the dynamic query based on the 'requestedBy' field and sorts by 'createdAt'
    List<ExternalRequest> findByRequestedByOrderByCreatedAtDesc(String requestedBy);

    // Also ensures your existing status list works
    List<ExternalRequest> findByStatusOrderByCreatedAtDesc(String status);
}

