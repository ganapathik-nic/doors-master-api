package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Cookie;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.apache.commons.codec.digest.DigestUtils;

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
import org.gepnic.doors.masterapi.dto.MfaVerifyRequest;
import org.gepnic.doors.masterapi.service.TotpService;
import org.gepnic.doors.masterapi.config.ManagerPlaneAccess;
import org.gepnic.doors.masterapi.config.PlaneRolePolicy;
import org.gepnic.doors.masterapi.config.PlaneHostResolver;
import java.time.Instant;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String SESSION_COOKIE = "DOORS_SESSION";
    private static final long SESSION_MAX_AGE_SECONDS = 8 * 60 * 60;

    @Value("${doors.security.session-cookie-secure:true}")
    private boolean secureSessionCookie;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final AgentRepository agentRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AuthenticationEventPublisher eventPublisher;
    private final CaptchaService captchaService;
    private final TotpService totpService;
    private final ManagerPlaneAccess managerPlaneAccess;
    private final PlaneRolePolicy planeRolePolicy;
    // Simple local cache: Key = CaptchaID, Value = ExpectedText
    private final Map<String, String> captchaCache = new ConcurrentHashMap<>();
    private final Map<String, MfaChallenge> mfaChallenges = new ConcurrentHashMap<>();

    private record MfaChallenge(String username, String enrollmentSecret, Instant expiresAt, int attempts) {}

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
    public ResponseEntity<?> logout(Authentication authentication, HttpServletRequest request) {
        String username = resolveLogoutUsername(authentication, request);
        log.info("DOORS-AUTH: Manual logout request received for: {}", username);

        if (username != null && !username.isBlank()) try {
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

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearSessionCookie().toString())
                .body(Map.of("message", "Logged out successfully"));
    }

    private String resolveLogoutUsername(Authentication authentication, HttpServletRequest request) {
        if (authentication != null && authentication.isAuthenticated()) return authentication.getName();
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (!SESSION_COOKIE.equals(cookie.getName()) || cookie.getValue().isBlank()) continue;
            try {
                Map<String, Object> claims = jwtUtils.parseToken(cookie.getValue());
                return claims == null ? null : (String) claims.get("sub");
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken csrfToken) {
        return Map.of("token", csrfToken.getToken());
    }

    @GetMapping("/me")
    public ResponseEntity<?> currentUser(Authentication authentication, HttpServletRequest request) {
        String username = authentication.getName();
        return userRepository.findByUsername(username)
                .filter(user -> Boolean.TRUE.equals(user.getIsActive()))
                .map(user -> ResponseEntity.ok(Map.of(
                        "username", user.getUsername(),
                        "role", user.getRole(),
                        "mustChangePassword", Boolean.TRUE.equals(user.getPasswordResetRequired()),
                        "sessionCryptoKey", buildSessionCryptoKey(readSessionToken(request))
                )))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest, HttpServletRequest httpRequest) {
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

                    // Accept the canonical BCrypt(SHA-256(password)) format. A
                    // successful legacy BCrypt(raw password) login is upgraded
                    // below, since stored BCrypt hashes cannot be migrated
                    // without first verifying the user's plaintext password.
                    boolean canonicalPassword = passwordEncoder.matches(
                            passwordHashToMatch, user.getPasswordHash());
                    boolean legacyRawPassword = !canonicalPassword
                            && passwordEncoder.matches(decryptedRawPassword, user.getPasswordHash());
                    if (!canonicalPassword && !legacyRawPassword) {
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

                    if (!planeRolePolicy.isRoleAllowed(PlaneHostResolver.resolve(httpRequest), user.getRole())) {
                        captchaCache.remove(saltKey);
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                                "code", "DOORS-ROLE-PLANE-DENIED",
                                "message", "This account is not permitted on this portal"));
                    }

                    if (legacyRawPassword) {
                        user.setPasswordHash(passwordEncoder.encode(passwordHashToMatch));
                        log.info("DOORS-AUTH: Upgraded legacy password hash for user [{}]", user.getUsername());
                    }

                    // Password verification is only the first authentication
                    // factor. Do not create a portal session until TOTP succeeds.
                    captchaCache.remove(saltKey);
                    userRepository.save(user);
                    String challengeId = UUID.randomUUID().toString();
                    boolean enrollmentRequired = !Boolean.TRUE.equals(user.getMfaEnabled())
                            || user.getMfaSecretEncrypted() == null;
                    String enrollmentSecret = enrollmentRequired ? totpService.generateSecret() : null;
                    mfaChallenges.put(challengeId, new MfaChallenge(
                            user.getUsername(), enrollmentSecret, Instant.now().plusSeconds(300), 0));

                    Map<String, Object> response = new HashMap<>();
                    response.put("mfaRequired", !enrollmentRequired);
                    response.put("mfaEnrollmentRequired", enrollmentRequired);
                    response.put("challengeId", challengeId);
                    response.put("expiresInSeconds", 300);
                    if (enrollmentRequired) {
                        response.put("secret", enrollmentSecret);
                        response.put("provisioningUri",
                                totpService.provisioningUri(user.getUsername(), enrollmentSecret));
                    }
                    return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);

                } catch (Exception e) {
                    log.error("DOORS-AUTH-CRITICAL: Login process error: {}", e.getMessage());
                    return ResponseEntity.status(500).body(Map.of("message", "Internal server error"));
                }
            })
            .orElseGet(() -> {
                captchaCache.remove(saltKey);
                return ResponseEntity.status(401).body(Map.of("message", "Invalid credentials"));
            });
    }

    @PostMapping("/mfa/verify")
    public ResponseEntity<?> verifyMfa(@RequestBody MfaVerifyRequest request,
                                       HttpServletRequest httpRequest) {
        if (request == null || request.challengeId() == null || request.code() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "MFA challenge and code are required"));
        }
        MfaChallenge challenge = mfaChallenges.get(request.challengeId());
        if (challenge == null || challenge.expiresAt().isBefore(Instant.now())) {
            mfaChallenges.remove(request.challengeId());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "MFA challenge expired. Sign in again."));
        }
        if (challenge.attempts() >= 5) {
            mfaChallenges.remove(request.challengeId());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("message", "Too many MFA attempts. Sign in again."));
        }

        return userRepository.findByUsername(challenge.username()).map(user -> {
            if (!planeRolePolicy.isRoleAllowed(PlaneHostResolver.resolve(httpRequest), user.getRole())) {
                mfaChallenges.remove(request.challengeId());
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "code", "DOORS-ROLE-PLANE-DENIED",
                        "message", "This account is not permitted on this portal"));
            }
            if (isPrivilegedRole(user.getRole()) && !managerPlaneAccess.isAllowed(httpRequest)) {
                mfaChallenges.remove(request.challengeId());
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "code", "DOORS-MANAGER-PLANE-REQUIRED",
                        "message", "Privileged access requires the NIC VPN manager portal"));
            }
            if ((user.getRole().equalsIgnoreCase("DataManager") || user.getRole().equalsIgnoreCase("ADMIN"))
                    && !hasVpnAccess(user.getVpnStatus())) {
                mfaChallenges.remove(request.challengeId());
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "code", "DOORS-VPN-ENTITLEMENT-REQUIRED",
                        "message", "NIC VPN entitlement has not been confirmed"));
            }
            if (user.getVpnIp() != null && !user.getVpnIp().isBlank()
                    && !managerPlaneAccess.isVpnIpAllowed(httpRequest, user.getVpnIp())) {
                mfaChallenges.remove(request.challengeId());
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "code", "DOORS-IP-NOT-ALLOWED",
                        "message", "Current IP is not whitelisted for this account"));
            }
            String secret = challenge.enrollmentSecret() != null
                    ? challenge.enrollmentSecret()
                    : totpService.decryptSecret(user.getMfaSecretEncrypted());
            if (!totpService.verify(secret, request.code())) {
                mfaChallenges.put(request.challengeId(), new MfaChallenge(
                        challenge.username(), challenge.enrollmentSecret(), challenge.expiresAt(), challenge.attempts() + 1));
                eventPublisher.publishAuthenticationFailure(
                        new BadCredentialsException("Invalid MFA code"),
                        new UsernamePasswordAuthenticationToken(challenge.username(), null));
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "Invalid verification code"));
            }

            mfaChallenges.remove(request.challengeId());
            if (challenge.enrollmentSecret() != null) {
                user.setMfaSecretEncrypted(totpService.encryptSecret(secret));
                user.setMfaEnabled(true);
            }
            return completeLogin(user);
        }).orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("message", "Account is unavailable")));
    }

    private boolean hasVpnAccess(String vpnStatus) {
        return "CONFIRMED".equalsIgnoreCase(vpnStatus)
                || "GRACE_PERIOD".equalsIgnoreCase(vpnStatus);
    }

    private boolean isPrivilegedRole(String role) {
        return role != null && (role.equalsIgnoreCase("DataManager")
                || role.equalsIgnoreCase("ADMIN")
                || role.equalsIgnoreCase("SecurityAdmin"));
    }

    private ResponseEntity<?> completeLogin(User user) {
        String newSessionId = UUID.randomUUID().toString();
        user.setCurrentSessionId(newSessionId);
        userRepository.save(user);
        String realToken = jwtUtils.generateToken(user.getUsername(), user.getRole(), newSessionId);
        eventPublisher.publishAuthenticationSuccess(
                new UsernamePasswordAuthenticationToken(user.getUsername(), null, new ArrayList<>()));
        log.info("DOORS-AUTH-SUCCESS: User [{}] completed MFA", user.getUsername());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(realToken).toString())
                .body(Map.of(
                        "username", user.getUsername(),
                        "role", user.getRole(),
                        "mustChangePassword", Boolean.TRUE.equals(user.getPasswordResetRequired()),
                        "mfaEnabled", true));
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody Map<String, String> request,
                                            Authentication authentication) {
        String username = authentication.getName();
        String oldPassword = request.get("oldPassword");
        String newPassword = request.get("newPassword");

        if (username == null || username.isBlank()
                || oldPassword == null || oldPassword.isBlank()
                || newPassword == null || newPassword.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "All password fields are required"));
        }

        return userRepository.findByUsername(username)
            .map(user -> {
                String oldPasswordDigest = DigestUtils.sha256Hex(oldPassword);
                String newPasswordDigest = DigestUtils.sha256Hex(newPassword);
                if (!passwordEncoder.matches(oldPasswordDigest, user.getPasswordHash())) {
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "Current password is incorrect"));
                }

                if (oldPasswordDigest.equals(newPasswordDigest)) {
                    return ResponseEntity.badRequest()
                        .body(Map.of("message", "New password must be different from the current password"));
                }

                String policyViolation = org.gepnic.doors.masterapi.util.PasswordPolicy.violation(newPassword);
                if (policyViolation != null) {
                    return ResponseEntity.badRequest().body(Map.of("message", policyViolation));
                }

                user.setPasswordHash(passwordEncoder.encode(newPasswordDigest));
                user.setPasswordResetRequired(false);
                user.setCurrentSessionId(null);
                userRepository.save(user);

                log.info("DOORS-AUTH: Password changed successfully for user [{}]", username);
                return ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, clearSessionCookie().toString())
                        .body(Map.of("message", "Password updated successfully"));
            })
            .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("message", "Current password is incorrect")));
    }

    private ResponseCookie sessionCookie(String token) {
        return ResponseCookie.from(SESSION_COOKIE, token)
                .httpOnly(true)
                .secure(secureSessionCookie)
                .sameSite("Strict")
                .path("/")
                .maxAge(SESSION_MAX_AGE_SECONDS)
                .build();
    }

    private ResponseCookie clearSessionCookie() {
        return ResponseCookie.from(SESSION_COOKIE, "")
                .httpOnly(true)
                .secure(secureSessionCookie)
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .build();
    }

    private String readSessionToken(HttpServletRequest request) {
        if (request.getCookies() == null) return "";
        return java.util.Arrays.stream(request.getCookies())
                .filter(cookie -> SESSION_COOKIE.equals(cookie.getName()))
                .map(jakarta.servlet.http.Cookie::getValue)
                .findFirst()
                .orElse("");
    }

    private String buildSessionCryptoKey(String token) {
        if (token.length() < 8) {
            throw new IllegalStateException("Authenticated session is missing cryptographic context");
        }
        return "D00RS-NI" + token.substring(token.length() - 8);
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
            if (!java.util.Set.of("External", "Developer", "DataViewer", "DataManager")
                    .contains(regRequest.role())) {
                return ResponseEntity.badRequest().body(Map.of("message", "Unsupported requested role"));
            }
            User user = new User();
            user.setName(regRequest.name());
            user.setEmail(regRequest.email());
            user.setOrg(regRequest.org());
            user.setRole(regRequest.role());
            user.setStatus("DataManager".equals(regRequest.role())
                    ? "PENDING_SECURITY_APPROVAL"
                    : "PENDING");
            user.setVpnStatus("DataManager".equals(regRequest.role())
                    ? "PENDING_VPN_CONFIRMATION"
                    : "NOT_REQUIRED");
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
