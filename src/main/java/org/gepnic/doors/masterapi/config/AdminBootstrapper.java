package org.gepnic.doors.masterapi.config;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.model.User;
import org.gepnic.doors.masterapi.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;

@Configuration
@RequiredArgsConstructor
public class AdminBootstrapper {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder; // Injected here

    @Value("${doors.security.bootstrap-legacy-admin-enabled:false}")
    private boolean bootstrapLegacyAdminEnabled;

    @Value("${doors.security.bootstrap-security-admin.username:}")
    private String securityAdminUsername;

    @Value("${doors.security.bootstrap-security-admin.email:}")
    private String securityAdminEmail;

    @Value("${doors.security.bootstrap-security-admin.password:}")
    private String securityAdminPassword;

    @Bean
    public CommandLineRunner initAdmin() {
        return args -> {
            if (!bootstrapLegacyAdminEnabled) return;
            if (userRepository.findByUsername("admin").isEmpty()) {
                User admin = new User();
                admin.setUsername("admin");
                
                // HASH THE PASSWORD HERE
                String securePassword = passwordEncoder.encode(
                        DigestUtils.sha256Hex("admin123"));
                admin.setPasswordHash(securePassword); 
                
                admin.setEmail("admin@nic.in");
                admin.setName("Master Admin");
                admin.setOrg("NIC");
                admin.setRole("ADMIN");
                admin.setStatus("APPROVED");
                admin.setIsActive(true);
                admin.setPasswordResetRequired(true);
                
                userRepository.save(admin);
                
                System.out.println("---------------------------------------------------------");
                System.out.println(">>> DOORS SYSTEM: Admin Bootstrapped with BCrypt Hashing.");
                System.out.println("---------------------------------------------------------");
            }
        };
    }

    @Bean
    public CommandLineRunner initSecurityAdmin() {
        return args -> {
            if (securityAdminUsername == null || securityAdminUsername.isBlank()) return;
            if (securityAdminPassword == null || securityAdminPassword.length() < 12) {
                throw new IllegalStateException("Bootstrap Security Administrator password must contain at least 12 characters");
            }
            if (userRepository.findByUsername(securityAdminUsername).isPresent()) return;

            User securityAdmin = new User();
            securityAdmin.setUsername(securityAdminUsername.trim());
            securityAdmin.setEmail(securityAdminEmail == null || securityAdminEmail.isBlank()
                    ? securityAdminUsername.trim()
                    : securityAdminEmail.trim());
            securityAdmin.setName("DOORS Security Administrator");
            securityAdmin.setOrg("NIC Security");
            securityAdmin.setRole("SecurityAdmin");
            securityAdmin.setStatus("ACTIVE");
            securityAdmin.setIsActive(true);
            securityAdmin.setPasswordResetRequired(true);
            securityAdmin.setMfaEnabled(false);
            securityAdmin.setVpnStatus("CONFIRMED");
            securityAdmin.setPasswordHash(passwordEncoder.encode(
                    DigestUtils.sha256Hex(securityAdminPassword)));
            userRepository.save(securityAdmin);
        };
    }
}
