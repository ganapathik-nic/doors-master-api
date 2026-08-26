package org.gepnic.doors.masterapi.repository;

import org.gepnic.doors.masterapi.model.DocumentDownloadPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentDownloadPolicyRepository extends JpaRepository<DocumentDownloadPolicy, Long> {
    Optional<DocumentDownloadPolicy> findByPolicyCodeIgnoreCase(String policyCode);
    boolean existsByPolicyCodeIgnoreCase(String policyCode);
    List<DocumentDownloadPolicy> findAllByOrderByPolicyNameAsc();
}
