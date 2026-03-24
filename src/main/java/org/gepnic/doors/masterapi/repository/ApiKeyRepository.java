package org.gepnic.doors.masterapi.repository;

import org.gepnic.doors.masterapi.model.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {
    // Used by the Gateway to validate incoming headers
    boolean existsByApiKeyAndIsActiveTrue(String apiKey);
    
    // Used for management/lookup
    Optional<ApiKey> findByApiKey(String apiKey);
}