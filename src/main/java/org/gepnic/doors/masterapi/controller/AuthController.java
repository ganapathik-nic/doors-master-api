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

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import org.gepnic.doors.masterapi.exception.EncryptionException;

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
            // 🚀 Clear the session ID in DB to invalidate the current token immediately
            userRepository.findByUsername(username).ifPresent(user -> {
                user.setCurrentSessionId(null);
                userRepository.save(user);
            });
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
        if (expectedText == null || !expectedText.equals(loginRequest.captchaValue())) {
            if (loginRequest.captchaId() != null) {
                captchaCache.remove(loginRequest.captchaId()); 
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", "Invalid or expired verification code"));
        }

        String saltKey = loginRequest.captchaId();

        return userRepository.findByUsername(loginRequest.username())
            .map(user -> {
                try {
                    // 🛡️ 2. Decrypt the AES payload sent by Vue
                    String encryptedPwdFromUi = loginRequest.password();
                    String decryptedRawPassword;
                    
                    try {
                        // Decrypt and clean hidden characters/whitespace
                        decryptedRawPassword = decryptAES(encryptedPwdFromUi, saltKey).trim().replaceAll("\0", "");
                    } catch (Exception e) {
                        log.error("DOORS-AUTH-ERROR: Handshake decryption failed for user {}: {}", user.getUsername(), e.getMessage());
                        return ResponseEntity.status(401).body(Map.of("message", "Security handshake failed"));
                    }

                    // 🛡️ 3. Aligned Hashing (The Double-Layer Fix)
                    // Since your DB stores BCrypt(SHA256(password)), we must SHA256 the decrypted string first.
                    String passwordHashToMatch = org.apache.commons.codec.digest.DigestUtils.sha256Hex(decryptedRawPassword);

                    // 🛡️ 4. BCrypt Match against the Database
                    if (!passwordEncoder.matches(passwordHashToMatch, user.getPasswordHash())) {
                        log.warn("DOORS-AUTH: Password mismatch for user: {}", user.getUsername());
                        captchaCache.remove(saltKey);
                        eventPublisher.publishAuthenticationFailure(
                            new BadCredentialsException("Invalid credentials"),
                            new UsernamePasswordAuthenticationToken(loginRequest.username(), null)
                        );
                        return ResponseEntity.status(401).body(Map.of("message", "Invalid credentials"));
                    }

                    // 🛡️ 5. Status Check
                    if ("PENDING".equals(user.getStatus()) || Boolean.FALSE.equals(user.getIsActive())) {
                        captchaCache.remove(saltKey);
                        return ResponseEntity.status(403).body(Map.of("message", "Account pending approval"));
                    }

                    // 🛡️ 6. Success: Burn Salt and Manage Session
                    captchaCache.remove(saltKey);
                    String newSessionId = UUID.randomUUID().toString();
                    user.setCurrentSessionId(newSessionId);
                    userRepository.save(user);

                    // 🛡️ 7. Token Generation
                    String realToken = jwtUtils.generateToken(user.getUsername(), user.getRole(), newSessionId);
                    
                    eventPublisher.publishAuthenticationSuccess(
                        new UsernamePasswordAuthenticationToken(user.getUsername(), null, new ArrayList<>())
                    );

                    // 🛡️ 8. Response
                    Map<String, Object> response = new HashMap<>();
                    response.put("username", user.getUsername());
                    response.put("role", user.getRole());
                    response.put("mustChangePassword", user.getPasswordResetRequired());
                    response.put("token", realToken);

                    log.info("DOORS-AUTH-SUCCESS: User [{}] logged in successfully", user.getUsername());
                    return ResponseEntity.ok(response);

                } catch (Exception e) {
                    log.error("DOORS-AUTH-CRITICAL: Login process error: {}", e.getMessage());
                    return ResponseEntity.status(500).body(Map.of("message", "Internal server error"));
                }
            })
            .orElseGet(() -> {
                captchaCache.remove(saltKey);
                return ResponseEntity.status(401).body(Map.of("message", "User not found"));
            });
    }
    private String decryptAES(String encryptedText, String key) throws Exception {
        byte[] cipherData = java.util.Base64.getDecoder().decode(encryptedText);
        byte[] salt = java.util.Arrays.copyOfRange(cipherData, 8, 16);

        MessageDigest md5 = MessageDigest.getInstance("MD5");
        final byte[][] keyAndIV = deriveKeyAndIV(32, 16, 1, salt, key.getBytes(StandardCharsets.UTF_8), md5);
        SecretKeySpec keySpec = new SecretKeySpec(keyAndIV[0], "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(keyAndIV[1]);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        
        byte[] encrypted = java.util.Arrays.copyOfRange(cipherData, 16, cipherData.length);
        byte[] decrypted = cipher.doFinal(encrypted);
        
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    private byte[][] deriveKeyAndIV(int keyLength, int ivLength, int iterations, byte[] salt, byte[] password, MessageDigest md) {
        int targetKeySize = keyLength + ivLength;
        byte[] derivedBytes = new byte[targetKeySize];
        int numberOfDerivedBytes = 0;
        byte[] md5Hash = new byte[0];
        while (numberOfDerivedBytes < targetKeySize) {
            md.reset();
            md.update(md5Hash);
            md.update(password);
            md.update(salt);
            md5Hash = md.digest();
            for (int i = 1; i < iterations; i++) {
                md.reset();
                md.update(md5Hash);
                md5Hash = md.digest();
            }
            int remainingKeySize = targetKeySize - numberOfDerivedBytes;
            int copyLength = Math.min(md5Hash.length, remainingKeySize);
            System.arraycopy(md5Hash, 0, derivedBytes, numberOfDerivedBytes, copyLength);
            numberOfDerivedBytes += copyLength;
        }
        byte[][] keyAndIV = new byte[2][];
        keyAndIV[0] = java.util.Arrays.copyOfRange(derivedBytes, 0, keyLength);
        keyAndIV[1] = java.util.Arrays.copyOfRange(derivedBytes, keyLength, keyLength + ivLength);
        return keyAndIV;
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