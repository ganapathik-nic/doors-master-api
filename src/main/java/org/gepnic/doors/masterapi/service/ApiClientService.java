package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.repository.ApiClientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiClientService {

    private final ApiClientRepository apiClientRepository;
    
    // The SecureRandom instance should be static to avoid re-seeding overhead
    private static final SecureRandom secureRandom = new SecureRandom();

    /**
     * Registers a new API client with an auto-generated secure API key.
     */
    @Transactional
    public ApiClient registerClient(String name, String description, String createdBy) {
        ApiClient client = new ApiClient();
        client.setClientName(name);
        client.setDescription(description);
        client.setCreatedBy(createdBy);
        client.setIsActive(true);
        
        // Generate the secure X-API-KEY (Passport)
        client.setApiKey(generateKey());
        
        log.info("DOORS-SECURITY: Registering new API client: {}", name);
        return apiClientRepository.save(client);
    }

    /**
     * 🚀 VER 1.0.1: Updates the IP Whitelist (allowed_ips) for a specific client.
     * Maps to the 'allowed_ips' column in the external_api_clients table.
     */
   @Transactional
public void updateIpWhitelist(Long clientId, String ipWhitelist) {
    ApiClient client = apiClientRepository.findById(clientId)
            .orElseThrow(() -> new RuntimeException("Client not found"));

    String sanitized = (ipWhitelist != null) ? ipWhitelist.replaceAll("\\s*,\\s*", ",") : "";
    
    // 🚀 LOG THIS: Verify the service actually receives the data
    log.info("DOORS-SERVICE: Setting Allowed IPs to: {}", sanitized);
    
    client.setAllowedIps(sanitized);

    // 🚀 CHANGE: Use saveAndFlush to force the SQL log
    apiClientRepository.saveAndFlush(client); 
}
    /**
     * Generates a high-entropy 32-character secure string.
     */
    private String generateKey() {
        byte[] bytes = new byte[24]; 
        secureRandom.nextBytes(bytes);
        // Using UrlEncoder to ensure the key is safe for headers/URLs
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}