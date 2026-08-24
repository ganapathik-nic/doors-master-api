package org.gepnic.doors.masterapi.repository;

import org.gepnic.doors.masterapi.entity.ApiClient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.List;

@Repository
public interface ApiClientRepository extends JpaRepository<ApiClient, Long> {

    // Used by ExternalConsumerController to find the client by their key
    Optional<ApiClient> findByApiKey(String apiKey);

    List<ApiClient> findByIsActiveTrueOrderByClientNameAsc();

    // Used by ApiKeyInterceptor for high-performance security checks
    boolean existsByApiKeyAndIsActiveTrue(String apiKey);

    /**
     * 🚀 THE FIXED COMPLIANCE BRIDGE:
     * Added explicitly to resolve the compilation error on line 51 of ExternalGatewayController.
     * Uses @Query to point directly to the underlying 'isActive' property regardless of method naming.
     */
    @Query("SELECT COUNT(c) > 0 FROM ApiClient c WHERE c.apiKey = :apiKey AND c.isActive = true")
    boolean existsByApiKeyAndActiveTrue(@Param("apiKey") String apiKey);

    /**
     * 🚀 HIGH-PERFORMANCE OPTIMIZATION:
     * Directly check if encryption is enabled for an active client key without loading 
     * the entire ApiClient entity into the Hibernate persistence context.
     */
    @Query("SELECT c.isEncryptionEnabled FROM ApiClient c WHERE c.apiKey = :apiKey AND c.isActive = true")
    Optional<Boolean> checkEncryptionStatusByApiKey(@Param("apiKey") String apiKey);
}
