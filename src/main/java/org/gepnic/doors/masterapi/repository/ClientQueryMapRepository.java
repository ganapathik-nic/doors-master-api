 package org.gepnic.doors.masterapi.repository;

import org.gepnic.doors.masterapi.entity.ClientQueryMap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query; 
public interface ClientQueryMapRepository extends JpaRepository<ClientQueryMap, Long> {
    
    // Checks if a client is authorized to run a specific query
    boolean existsByClientIdAndQueryId(Long clientId, Long queryId);

    boolean existsByQueryId(Long queryId);
    
    // Retrieves all active permissions for a specific client
    List<ClientQueryMap> findByClientId(Long clientId);

    Optional<ClientQueryMap> findByClientIdAndQueryId(Long clientId, Long queryId);
    
    // REQUIRED: @Modifying and @Transactional for delete operations
    @Modifying
    @Transactional
    void deleteByClientIdAndQueryId(Long clientId, Long queryId);

    @Modifying
@Transactional
@Query("DELETE FROM ClientQueryMap c WHERE c.clientId = ?1")
void deleteByClientId(Long clientId);
}
