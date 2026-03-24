package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.model.User;
import org.gepnic.doors.masterapi.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class UserAgentMappingController {
    
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    @GetMapping("/master/users/all")
    public ResponseEntity<ApiResponse<List<User>>> getAllUsers() {
        log.info("DOORS-ADMIN: Fetching all users for Matrix view");
        List<User> users = userRepository.findAll();
        return ResponseEntity.ok(ApiResponse.success(users, "User list retrieved"));
    }

    /**
     * Path: /api/v1/admin/mappings/user/{userId}/agents
     * Uses JdbcTemplate to ensure data is stored in 'user_name' column
     * without affecting JPA User Entity logic.
     */
    @Transactional
    @PutMapping("/admin/mappings/user/{userId}/agents")
    public ResponseEntity<ApiResponse<?>> updateMappings(
            @PathVariable Long userId, 
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