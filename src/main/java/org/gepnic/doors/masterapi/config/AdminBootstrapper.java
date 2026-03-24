package org.gepnic.doors.masterapi.config;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.model.User;
import org.gepnic.doors.masterapi.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@RequiredArgsConstructor
public class AdminBootstrapper {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder; // Injected here

    @Bean
    public CommandLineRunner initAdmin() {
        return args -> {
            if (userRepository.findByUsername("admin").isEmpty()) {
                User admin = new User();
                admin.setUsername("admin");
                
                // HASH THE PASSWORD HERE
                String securePassword = passwordEncoder.encode("admin123");
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
                System.out.println(">>> Password stored as: " + securePassword);
                System.out.println("---------------------------------------------------------");
            }
        };
    }
}