package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.config.JwtUtils;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.dto.CaptchaResponse;
import org.gepnic.doors.masterapi.dto.LoginRequest;
import org.gepnic.doors.masterapi.dto.RegistrationRequest;
import org.gepnic.doors.masterapi.model.User;
import org.gepnic.doors.masterapi.repository.AgentRepository;
import org.gepnic.doors.masterapi.repository.UserRepository;
import org.gepnic.doors.masterapi.service.CaptchaService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private final CaptchaService captchaService;
    // Simple local cache: Key = CaptchaID, Value = ExpectedText
    private final Map<String, String> captchaCache = new ConcurrentHashMap<>();

    @GetMapping("/captcha")
    public ResponseEntity<CaptchaResponse> getCaptcha() {
        String captchaId = UUID.randomUUID().toString();
        String captchaText = captchaService.generateText();
        
        // Store in cache for 2 minutes (you can add a cleanup task later)
        captchaCache.put(captchaId, captchaText);
        
        String base64Image = captchaService.generateBase64Image(captchaText);
        return ResponseEntity.ok(new CaptchaResponse(captchaId, base64Image));
    }
    
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
        // 🛡️ 1. Verify Captcha
        String expectedText = captchaCache.get(loginRequest.captchaId());
        if (expectedText == null || !expectedText.equalsIgnoreCase(loginRequest.captchaValue())) {
            if (loginRequest.captchaId() != null) {
                captchaCache.remove(loginRequest.captchaId()); 
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                 .body(Map.of("message", "Invalid or expired verification code"));
        }
        
        captchaCache.remove(loginRequest.captchaId());
        log.info("DOORS-AUTH: Login attempt for user: {}", loginRequest.username());
        
        return userRepository.findByUsername(loginRequest.username())
            .map(user -> {

                // 🛡️ 2. Password Check (Standard BCrypt Match)
                // loginRequest.password() is the SHA-256 string from Vue
            
                if (!passwordEncoder.matches(loginRequest.password(), user.getPasswordHash())) {
                    eventPublisher.publishAuthenticationFailure(
                        new BadCredentialsException("Invalid credentials"),
                        new UsernamePasswordAuthenticationToken(loginRequest.username(), null)
                    );
                    return ResponseEntity.status(401).body(Map.of("message", "Invalid credentials"));
                }

                // 🛡️ 3. Status Check
                if ("PENDING".equals(user.getStatus()) || Boolean.FALSE.equals(user.getIsActive())) {
                    return ResponseEntity.status(403).body(Map.of("message", "Account pending approval"));
                }

                // 🛡️ 4. Generate Token
                String realToken = jwtUtils.generateToken(user.getUsername(), user.getRole());

                // 🚀 Audit Success
                eventPublisher.publishAuthenticationSuccess(
                    new UsernamePasswordAuthenticationToken(user.getUsername(), null, new ArrayList<>())
                );

                // 🛡️ 5. Prepare Response
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
    public ResponseEntity<?> registerUser(@RequestBody RegistrationRequest regRequest) {
        // 🛡️ 1. CAPTCHA VERIFICATION
    String expectedText = captchaCache.get(regRequest.captchaId());
    
    if (expectedText == null || !expectedText.equalsIgnoreCase(regRequest.captchaValue())) {
        if (regRequest.captchaId() != null) {
            captchaCache.remove(regRequest.captchaId()); 
        }
        return ResponseEntity.badRequest().body(Map.of("message", "Invalid verification code"));
    }
    
    // 🛡️ 2. BURN TOKEN & PROCEED
    captchaCache.remove(regRequest.captchaId());
    // 🚀 3. UNIQUE EMAIL CHECK (Place it here!)
        // This ensures the user gets a 409 Conflict instead of a generic 500 error
        if (userRepository.findByUsername(regRequest.email()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                                 .body(Map.of("message", "This email is already registered."));
        }
        try {
            User user = new User();
            user.setName(regRequest.name());
            user.setEmail(regRequest.email());
            user.setOrg(regRequest.org());
            user.setRole(regRequest.role());
            user.setStatus("PENDING");
            user.setUsername(regRequest.email()); 
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