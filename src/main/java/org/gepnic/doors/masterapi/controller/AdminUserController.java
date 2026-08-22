package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.util.EncryptionUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder; // Added Import
import org.springframework.web.bind.annotation.*;
import org.gepnic.doors.masterapi.repository.UserRepository;
import org.gepnic.doors.masterapi.model.User;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import java.time.LocalDateTime;
import java.util.Set;
import org.gepnic.doors.masterapi.service.SecurityAuditService;
import org.apache.commons.codec.digest.DigestUtils;
import org.gepnic.doors.masterapi.util.PasswordPolicy;

@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@Slf4j
public class AdminUserController {

    private static final Set<String> STANDARD_ROLES = Set.of("External", "Developer", "DataViewer");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder; // Added for password hashing
    private final SecurityAuditService securityAuditService;

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getUserList(
            @RequestParam(defaultValue = "PENDING") String status,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            Authentication authentication) {
        try {
            String hybridKey = buildHybridKey(authorizationHeader);
            List<Map<String, Object>> safeUsers = userRepository.findByStatus(status).stream()
                    .filter(user -> canGovern(authentication, user))
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
            log.error("Unable to encrypt the user list", exception);
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
        record.put("rejectionReason", user.getRejectionReason());
        record.put("passwordResetRequired", user.getPasswordResetRequired());
        record.put("name", user.getName());
        record.put("org", user.getOrg());
        record.put("description", user.getDescription());
        record.put("createdAt", user.getCreatedAt());
        record.put("assignedAgents", user.getAssignedAgents());
        record.put("mfaEnabled", Boolean.TRUE.equals(user.getMfaEnabled()));
        record.put("vpnIp", user.getVpnIp());
        record.put("vpnCertificateReference", user.getVpnCertificateReference());
        record.put("vpnStatus", user.getVpnStatus());
        record.put("privilegedApprovedBy", user.getPrivilegedApprovedBy());
        record.put("privilegedApprovedAt", user.getPrivilegedApprovedAt());
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

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approveUser(@PathVariable Integer id,
                                         @RequestBody(required = false) Map<String, String> body,
                                         Authentication authentication) {
        return userRepository.findById(id).map(user -> {
            if (!canGovern(authentication, user)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "You cannot approve this role"));
            }
            if (isSecurityAdminRole(user.getRole())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "Security Administrator creation requires controlled bootstrap"));
            }
            if (isDataManagerRole(user.getRole())) {
                String vpnIp = normalize(body == null ? null : body.get("vpnIp"));
                String certificateReference = normalize(body == null ? null : body.get("vpnCertificateReference"));
                if (vpnIp != null && !isValidIpOrCidr(vpnIp)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "message", "NIC VPN IP/CIDR is invalid"));
                }
                user.setVpnIp(vpnIp);
                user.setVpnCertificateReference(certificateReference);
                // Phase-I grace period: VPN details may be registered later. Do not
                // claim VPN confirmation unless both controlled values were supplied.
                user.setVpnStatus(vpnIp != null && certificateReference != null
                        ? "CONFIRMED"
                        : "GRACE_PERIOD");
                user.setPrivilegedApprovedBy(authentication.getName());
                user.setPrivilegedApprovedAt(LocalDateTime.now());
            }
            user.setStatus("ACTIVE");
            user.setIsActive(true);
            user.setPasswordResetRequired(true); 
            user.setCurrentSessionId(null);
            
            String temporaryPassword = body == null ? null : body.get("tempPassword");
            String policyViolation = PasswordPolicy.violation(temporaryPassword);
            if (policyViolation != null) {
                return ResponseEntity.badRequest().body(Map.of("message", policyViolation));
            }
            user.setPasswordHash(passwordEncoder.encode(DigestUtils.sha256Hex(temporaryPassword)));
            
            userRepository.save(user);
            securityAuditService.record(authentication.getName(),
                    isDataManagerRole(user.getRole()) ? "DATAMANAGER_APPROVED" : "USER_APPROVED",
                    user.getUsername(), 200);
            return ResponseEntity.ok(Map.of("message", "User activated successfully"));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> rejectUser(@PathVariable Integer id,
                                        @RequestBody(required = false) Map<String, String> body,
                                        Authentication authentication) {
        return userRepository.findById(id).map(user -> {
            if (!canGovern(authentication, user)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "You cannot revoke this role"));
            }
            String reason = (body != null) ? body.get("reason") : "No reason provided";
            user.setStatus("REJECTED");
            user.setIsActive(false);
            user.setRejectionReason(reason);
            user.setCurrentSessionId(null);
            if (isDataManagerRole(user.getRole())) user.setVpnStatus("REVOKED");
            userRepository.save(user);
            securityAuditService.record(authentication.getName(),
                    isDataManagerRole(user.getRole()) ? "DATAMANAGER_REVOKED" : "USER_REJECTED",
                    user.getUsername(), 200);
            return ResponseEntity.ok(Map.of("message", "User request rejected"));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/role")
    public ResponseEntity<?> updateRole(@PathVariable Integer id,
                                        @RequestBody Map<String, String> body,
                                        Authentication authentication) {
        return userRepository.findById(id).map(user -> {
            if (body.containsKey("role")) {
                String requestedRole = body.get("role");
                if (isSecurityAdminRole(requestedRole)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(Map.of("message", "Security Administrator cannot be assigned through the portal"));
                }
                if (isDataManagerRole(requestedRole)) {
                    if (!isSecurityAdmin(authentication)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("message", "Only Security Administrator may grant or revoke DataManager"));
                    }
                    user.setStatus("PENDING_SECURITY_APPROVAL");
                    user.setIsActive(false);
                    user.setVpnStatus("PENDING_VPN_CONFIRMATION");
                } else if (isDataManagerRole(user.getRole())) {
                    if (!isSecurityAdmin(authentication) || !STANDARD_ROLES.contains(requestedRole)) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("message", "Invalid DataManager role revocation"));
                    }
                    user.setStatus("PENDING");
                    user.setIsActive(false);
                    user.setVpnStatus("REVOKED");
                } else if (!STANDARD_ROLES.contains(requestedRole)) {
                    return ResponseEntity.badRequest().body(Map.of("message", "Unsupported role"));
                }
                user.setRole(requestedRole);
                user.setCurrentSessionId(null);
                userRepository.save(user);
                securityAuditService.record(authentication.getName(), "USER_ROLE_CHANGED",
                        user.getUsername(), 200);
                return ResponseEntity.ok(Map.of("message", "Role updated"));
            }
            return ResponseEntity.badRequest().body(Map.of("error", "Role field is missing"));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/mfa/reset")
    public ResponseEntity<?> resetMfa(@PathVariable Integer id, Authentication authentication) {
        return userRepository.findById(id).map(user -> {
            if (!canGovern(authentication, user)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "You cannot reset MFA for this role"));
            }
            user.setMfaEnabled(false);
            user.setMfaSecretEncrypted(null);
            user.setCurrentSessionId(null);
            userRepository.save(user);
            securityAuditService.record(authentication.getName(), "MFA_RESET", user.getUsername(), 200);
            return ResponseEntity.ok(Map.of("message", "MFA reset; re-enrollment required at next login"));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/password/reset")
    public ResponseEntity<?> resetPassword(@PathVariable Integer id,
                                           @RequestBody Map<String, String> body,
                                           Authentication authentication) {
        return userRepository.findById(id).map(user -> {
            if (!canGovern(authentication, user)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "You cannot reset the password for this role"));
            }
            String temporaryPassword = body == null ? null : body.get("temporaryPassword");
            String policyViolation = PasswordPolicy.violation(temporaryPassword);
            if (policyViolation != null) {
                return ResponseEntity.badRequest().body(Map.of("message", policyViolation));
            }
            user.setPasswordHash(passwordEncoder.encode(DigestUtils.sha256Hex(temporaryPassword)));
            user.setPasswordResetRequired(true);
            user.setCurrentSessionId(null);
            userRepository.save(user);
            securityAuditService.record(authentication.getName(), "PASSWORD_RESET", user.getUsername(), 200);
            return ResponseEntity.ok(Map.of("message", "Temporary password set; user must change it at next login"));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/vpn")
    public ResponseEntity<?> updateVpnAccess(@PathVariable Integer id,
                                             @RequestBody Map<String, String> body,
                                             Authentication authentication) {
        return userRepository.findById(id).map(user -> {
            if (!isSecurityAdmin(authentication) || !isDataManagerRole(user.getRole())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "Only Security Administrator may manage DataManager VPN access"));
            }

            String action = normalize(body == null ? null : body.get("action"));
            if (action == null) action = "WHITELIST";
            if ("REMOVE".equalsIgnoreCase(action)) {
                user.setVpnIp(null);
                user.setVpnCertificateReference(null);
                user.setVpnStatus("REVOKED");
                user.setCurrentSessionId(null);
                userRepository.save(user);
                securityAuditService.record(authentication.getName(), "VPN_IP_REMOVED", user.getUsername(), 200);
                return ResponseEntity.ok(Map.of("message", "VPN IP removed and active session revoked"));
            }
            if (!"WHITELIST".equalsIgnoreCase(action)) {
                return ResponseEntity.badRequest().body(Map.of("message", "Unsupported VPN action"));
            }

            String vpnIp = normalize(body.get("vpnIp"));
            String certificateReference = normalize(body.get("vpnCertificateReference"));
            if (vpnIp == null || !isValidIpOrCidr(vpnIp)) {
                return ResponseEntity.badRequest().body(Map.of("message", "A valid VPN IP or CIDR is required"));
            }
            user.setVpnIp(vpnIp);
            user.setVpnCertificateReference(certificateReference);
            user.setVpnStatus("CONFIRMED");
            user.setPrivilegedApprovedBy(authentication.getName());
            user.setPrivilegedApprovedAt(LocalDateTime.now());
            user.setCurrentSessionId(null);
            userRepository.save(user);
            securityAuditService.record(authentication.getName(), "VPN_IP_WHITELISTED", user.getUsername(), 200);
            return ResponseEntity.ok(Map.of("message", "VPN IP whitelisted; user must sign in again"));
        }).orElse(ResponseEntity.notFound().build());
    }

    private boolean canGovern(Authentication authentication, User target) {
        if (isSecurityAdmin(authentication)) return isDataManagerRole(target.getRole());
        return isDataManager(authentication) && STANDARD_ROLES.contains(target.getRole());
    }

    private boolean isSecurityAdmin(Authentication authentication) {
        return hasAuthority(authentication, "SECURITYADMIN", "ROLE_SECURITYADMIN");
    }

    private boolean isDataManager(Authentication authentication) {
        return hasAuthority(authentication, "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN");
    }

    private boolean hasAuthority(Authentication authentication, String... accepted) {
        if (authentication == null) return false;
        Set<String> values = java.util.Arrays.stream(accepted).collect(java.util.stream.Collectors.toSet());
        return authentication.getAuthorities().stream().anyMatch(authority -> values.contains(authority.getAuthority()));
    }

    private boolean isDataManagerRole(String role) {
        return role != null && (role.equalsIgnoreCase("DataManager") || role.equalsIgnoreCase("ADMIN"));
    }

    private boolean isSecurityAdminRole(String role) {
        return role != null && role.equalsIgnoreCase("SecurityAdmin");
    }

    private String normalize(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }

    private boolean isValidIpOrCidr(String value) {
        String address = value.contains("/") ? value.substring(0, value.indexOf('/')) : value;
        String prefix = value.contains("/") ? value.substring(value.indexOf('/') + 1) : null;
        try {
            if (address.contains(".")) {
                String[] octets = address.split("\\.", -1);
                if (octets.length != 4) return false;
                for (String octet : octets) {
                    if (!octet.matches("\\d{1,3}") || Integer.parseInt(octet) > 255) return false;
                }
                return prefix == null || (Integer.parseInt(prefix) >= 0 && Integer.parseInt(prefix) <= 32);
            }
            if (!address.matches("[0-9A-Fa-f:]+") || !address.contains(":")) return false;
            java.net.InetAddress parsed = java.net.InetAddress.getByName(address);
            return parsed instanceof java.net.Inet6Address
                    && (prefix == null || (Integer.parseInt(prefix) >= 0 && Integer.parseInt(prefix) <= 128));
        } catch (Exception ignored) {
            return false;
        }
    }
}
