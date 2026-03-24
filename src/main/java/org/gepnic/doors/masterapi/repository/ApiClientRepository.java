package org.gepnic.doors.masterapi.repository;

import org.gepnic.doors.masterapi.entity.ApiClient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface ApiClientRepository extends JpaRepository<ApiClient, Long> {

    // Used by ExternalConsumerController to find the client by their key
    Optional<ApiClient> findByApiKey(String apiKey);

    // Used by ApiKeyInterceptor for high-performance security checks
    boolean existsByApiKeyAndIsActiveTrue(String apiKey);
}
