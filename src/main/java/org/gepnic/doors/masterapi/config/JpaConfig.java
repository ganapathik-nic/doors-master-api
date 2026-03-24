package org.gepnic.doors.masterapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import java.util.Optional;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider") // Activates auditing
public class JpaConfig {

    @Bean
    public AuditorAware<String> auditorProvider() {
        // For now, we return a system name. Later, this can pull from Spring Security Context
        return () -> Optional.of("DOORS_ADMIN"); 
    }
}