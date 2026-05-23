package org.gepnic.doors.masterapi.repository;

import org.gepnic.doors.masterapi.entity.DoorsSigningCertificate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface DoorsSigningCertificateRepository extends JpaRepository<DoorsSigningCertificate, Long> {
    
    Optional<DoorsSigningCertificate> findByIsActiveTrue();
    
    Optional<DoorsSigningCertificate> findByKeyId(String keyId);

    /**
     * 🚀 CUSTOM GOVERNANCE SORT: 
     * Pins the active key (isActive = true) to the top, 
     * then orders everything else by creation date descending.
     */
    @Query("SELECT c FROM DoorsSigningCertificate c ORDER BY " +
           "CASE WHEN c.isActive = true THEN 0 ELSE 1 END ASC, " +
           "c.createdAt DESC")
    List<DoorsSigningCertificate> findAllWithLivePinnedToTop();
}