package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder; // Added Import
import org.springframework.web.bind.annotation.*;
import org.gepnic.doors.masterapi.repository.UserRepository;
import org.gepnic.doors.masterapi.model.User;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder; // Added for password hashing

    @GetMapping("/list")
    public ResponseEntity<List<User>> getUserList(@RequestParam(defaultValue = "PENDING") String status) {
        return ResponseEntity.ok(userRepository.findByStatus(status));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approveUser(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        return userRepository.findById(id).map(user -> {
            user.setStatus("ACTIVE");
            user.setIsActive(true);
            user.setPasswordResetRequired(true); 
            
            // Handle the temporary password from UI
            if (body != null && body.containsKey("tempPassword")) {
                user.setPasswordHash(passwordEncoder.encode(body.get("tempPassword")));
            } else {
                // Fallback: if no password sent, set a default
                user.setPasswordHash(passwordEncoder.encode("Welcome@123"));
            }
            
            userRepository.save(user);
            return ResponseEntity.ok(Map.of("message", "User activated successfully"));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> rejectUser(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        return userRepository.findById(id).map(user -> {
            String reason = (body != null) ? body.get("reason") : "No reason provided";
            user.setStatus("REJECTED");
            user.setIsActive(false);
            user.setRejectionReason(reason);
            userRepository.save(user);
            return ResponseEntity.ok(Map.of("message", "User request rejected"));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/role")
    public ResponseEntity<?> updateRole(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return userRepository.findById(id).map(user -> {
            if (body.containsKey("role")) {
                user.setRole(body.get("role"));
                userRepository.save(user);
                return ResponseEntity.ok(Map.of("message", "Role updated"));
            }
            return ResponseEntity.badRequest().body(Map.of("error", "Role field is missing"));
        }).orElse(ResponseEntity.notFound().build());
    }
}