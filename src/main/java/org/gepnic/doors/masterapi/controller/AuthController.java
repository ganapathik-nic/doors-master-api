package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.config.JwtUtils;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.dto.LoginRequest;
import org.gepnic.doors.masterapi.dto.RegistrationRequest;
import org.gepnic.doors.masterapi.model.User;
import org.gepnic.doors.masterapi.repository.AgentRepository;
import org.gepnic.doors.masterapi.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final AgentRepository agentRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AuthenticationEventPublisher eventPublisher;
 @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        log.info("DOORS-AUTH: Manual logout request received for: {}", username);

        if (username == null || username.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Username is required"));
        }

        try {
            // 🚀 RAW SQL to bypass any JPA/Spring Transactional issues
            String sql = "INSERT INTO unified_audit_logs (" +
                         "event_type, username, query_name, endpoint, method, " +
                         "status_code, duration_ms, record_count, full_command, execution_time" +
                         ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)";
            
            int rows = jdbcTemplate.update(sql, 
                "AUTH_EVENT", 
                username, 
                "LOGOUT [SYSTEM]", 
                "/api/v1/auth/logout", 
                "POST", 
                200, 
                0L, 
                0, 
                "Manual Logout Triggered"
            );

            log.info("DOORS-AUTH-SUCCESS: Database confirmed {} row inserted for {}", rows, username);
        } catch (Exception e) {
            log.error("DOORS-AUTH-CRITICAL: Logout DB Insert failed: {}", e.getMessage());
            // If this logs a "Column does not exist" error, your table schema is different!
        }

        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest) {
        log.info("DOORS-AUTH: Login attempt for user: {}", loginRequest.username());
        
        return userRepository.findByUsername(loginRequest.username())
            .map(user -> {
                // 1. Password Check
                if (!passwordEncoder.matches(loginRequest.password(), user.getPasswordHash())) {
                    eventPublisher.publishAuthenticationFailure(
                        new BadCredentialsException("Invalid credentials"),
                        new UsernamePasswordAuthenticationToken(loginRequest.username(), null)
                    );
                    return ResponseEntity.status(401).body(Map.of("message", "Invalid credentials"));
                }

                // 2. Status Check
                if ("PENDING".equals(user.getStatus()) || Boolean.FALSE.equals(user.getIsActive())) {
                    return ResponseEntity.status(403).body(Map.of("message", "Account pending approval"));
                }

                // 3. Generate Token
                String realToken = jwtUtils.generateToken(user.getUsername(), user.getRole());

                // 🚀 Audit Success (Only happens here, once per login)
                eventPublisher.publishAuthenticationSuccess(
                    new UsernamePasswordAuthenticationToken(user.getUsername(), null, new ArrayList<>())
                );

                // 4. Prepare Response
                Map<String, Object> response = new HashMap<>();
                response.put("username", user.getUsername());
                response.put("role", user.getRole());
                response.put("mustChangePassword", user.getPasswordResetRequired());
                response.put("token", realToken);

                log.info("DOORS-AUTH: Login successful for user: {}", user.getUsername());
                return ResponseEntity.ok(response);
            })
            .orElseGet(() -> {
                eventPublisher.publishAuthenticationFailure(
                    new BadCredentialsException("User not found"),
                    new UsernamePasswordAuthenticationToken(loginRequest.username(), null)
                );
                return ResponseEntity.status(401).body(Map.of("message", "User not found"));
            });
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String oldPassword = request.get("oldPassword");
        String newPassword = request.get("newPassword");

        return userRepository.findByUsername(username).map(user -> {
            if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
                return ResponseEntity.status(401).body(Map.of("message", "Current password incorrect"));
            }
            user.setPasswordHash(passwordEncoder.encode(newPassword));
            user.setPasswordResetRequired(false);
            userRepository.save(user);
            return ResponseEntity.ok(Map.of("message", "Password updated successfully"));
        }).orElse(ResponseEntity.status(404).body(Map.of("message", "User not found")));
    }

    @GetMapping("/list/active")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getActiveAgents() {
        try {
            String sql = "SELECT agent_id as \"agentId\", display_name as \"displayName\" FROM agents WHERE status = 'ACTIVE'";
            List<Map<String, Object>> activeAgents = jdbcTemplate.queryForList(sql);
            return ResponseEntity.ok(ApiResponse.success(activeAgents, "Active agents retrieved"));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(ApiResponse.error("Could not load agents", 500));
        }
    }

    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@RequestBody RegistrationRequest request) {
        try {
            User user = new User();
            user.setName(request.name());
            user.setEmail(request.email());
            user.setOrg(request.org());
            user.setRole(request.role());
            user.setStatus("PENDING");
            user.setUsername(request.email()); 
            user.setPasswordHash("PENDING_APPROVAL"); 
            user.setIsActive(false);
            user.setPasswordResetRequired(true);
            userRepository.save(user);
            return ResponseEntity.ok(Map.of("message", "Registration request submitted successfully"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Could not save request"));
        }
    }
}