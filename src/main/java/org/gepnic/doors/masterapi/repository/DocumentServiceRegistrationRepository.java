package org.gepnic.doors.masterapi.repository;

import org.gepnic.doors.masterapi.model.DocumentServiceRegistration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentServiceRegistrationRepository extends JpaRepository<DocumentServiceRegistration, Long> {
    Optional<DocumentServiceRegistration> findByServiceNameIgnoreCase(String serviceName);
    List<DocumentServiceRegistration> findAllByAgentIdAndIsActiveTrueOrderByServiceNameAsc(String agentId);
    boolean existsByServiceNameIgnoreCase(String serviceName);
    boolean existsByServiceNameIgnoreCaseAndServiceIdNot(String serviceName, Long serviceId);
    List<DocumentServiceRegistration> findAllByOrderByServiceNameAsc();
}
