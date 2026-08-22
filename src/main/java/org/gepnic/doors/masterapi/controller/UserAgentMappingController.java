package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.model.User;
import org.gepnic.doors.masterapi.repository.UserRepository;
import org.gepnic.doors.masterapi.util.EncryptionUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class UserAgentMappingController {
    
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/master/users/all")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAllUsers(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        try {
            log.info("DOORS-ADMIN: Fetching all users for Matrix view");
            String hybridKey = buildHybridKey(authorizationHeader);
            List<Map<String, Object>> safeUsers = userRepository.findAll().stream()
                    .map(this::toSafeUserRecord)
                    .toList();
            String serializedUsers = new com.fasterxml.jackson.databind.ObjectMapper()
                    .findAndRegisterModules()
                    .writeValueAsString(safeUsers);

            Map<String, Object> encryptedEnvelope = new HashMap<>();
            encryptedEnvelope.put("isEncryptedPayload", true);
            encryptedEnvelope.put("secureData", EncryptionUtils.encrypt(serializedUsers, hybridKey));
            encryptedEnvelope.put("rowCount", safeUsers.size());
            return ResponseEntity.ok(ApiResponse.success(encryptedEnvelope, "User list retrieved securely"));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(401).body(ApiResponse.error(exception.getMessage(), 401));
        } catch (Exception exception) {
            log.error("Unable to encrypt the User-Agent Matrix user list", exception);
            return ResponseEntity.status(500).body(ApiResponse.error("Unable to secure the user list response", 500));
        }
    }

    private Map<String, Object> toSafeUserRecord(User user) {
        Map<String, Object> record = new HashMap<>();
        record.put("userId", user.getUserId());
        record.put("username", user.getUsername());
        record.put("email", user.getEmail());
        record.put("role", user.getRole());
        record.put("isActive", user.getIsActive());
        record.put("status", user.getStatus());
        record.put("name", user.getName());
        record.put("org", user.getOrg());
        record.put("assignedAgents", user.getAssignedAgents());
        return record;
    }

    private String buildHybridKey(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new IllegalArgumentException("Authorization token is required");
        }
        String rawJwt = authorizationHeader.replaceFirst("(?i)^Bearer\\s+", "").trim();
        if (rawJwt.length() < 8) {
            throw new IllegalArgumentException("Authorization token is invalid");
        }
        return "D00RS-NI" + rawJwt.substring(rawJwt.length() - 8);
    }

    /**
     * Path: /api/v1/admin/mappings/user/{userId}/agents
     * Uses JdbcTemplate to ensure data is stored in 'user_name' column
     * without affecting JPA User Entity logic.
     */
    @Transactional
    @PutMapping("/admin/mappings/user/{userId}/agents")
    public ResponseEntity<ApiResponse<?>> updateMappings(
            @PathVariable Integer userId, 
            @RequestBody List<String> agentIds) {
        
        log.info("DOORS-ADMIN: Updating matrix for User ID: {}", userId);
        
        Optional<User> userOpt = userRepository.findById(userId);
        
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            String username = user.getUsername();

            // 1. Surgical Delete using String username
            jdbcTemplate.update("DELETE FROM user_authorized_agents WHERE user_name = ?", username);

            // 2. Surgical Insert into user_name column
            if (agentIds != null && !agentIds.isEmpty()) {
                for (String agentId : agentIds) {
                    jdbcTemplate.update(
                        "INSERT INTO user_authorized_agents (user_id,user_name, agent_id, assigned_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                        username,username, agentId
                    );
                }
            }

            log.info("DOORS-ADMIN: Successfully updated 'user_name' column for: {}", username);
            return ResponseEntity.ok(ApiResponse.success(null, "User-Agent matrix updated successfully"));
        
        } else {
            log.error("DOORS-ADMIN: User ID {} not found", userId);
            return ResponseEntity.status(404)
                    .body(ApiResponse.error("User not found with ID: " + userId, 404));
        }
    }
}
