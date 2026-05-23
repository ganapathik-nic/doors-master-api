package org.gepnic.doors.masterapi.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.gepnic.doors.masterapi.repository.ApiClientRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.Collections;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor 
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final ApiClientRepository apiClientRepository;

    @Value("${doors.security.allowed-origins:http://localhost:5173}")
    private List<String> configuredAllowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // 🚀 HOT-SWAP ATTACHMENT: No more hardcoded strings!
        configuration.setAllowedOriginPatterns(configuredAllowedOrigins); 
        
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-API-KEY", "Accept", "Origin"));
        configuration.setAllowCredentials(true);
        configuration.setExposedHeaders(Arrays.asList("X-API-KEY", "Authorization", "X-Session-Status"));
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }    
    @Bean 
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // 1. CORS & CSRF (CORS must be first)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable()) 
            
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) 
            
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, authException) -> {
                    System.out.println(">>> SECURITY ENTRY POINT: Forbidden/Unauthorized access to: " + request.getRequestURI());
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\": \"Unauthorized\", \"message\": \"Authentication required\"}");
                })
            )
            .authorizeHttpRequests(auth -> auth
                // 2. PUBLIC & OPTIONS Whitelists
                .requestMatchers("/api/v1/master/gateway/.well-known/jwks.json").permitAll()
                .requestMatchers("/auth/bootstrap-hash").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/v1/auth/**", "/error", "/api/auth/captcha").permitAll() 
                .requestMatchers("/api/v1/master/aira/**").permitAll()
                .requestMatchers("/api/v1/master/reports/orchestrate/**").permitAll()
                .requestMatchers("/api/v1/master/gateway/orchestrate/**").permitAll()

                // ====================================================================
                // 🚀 FIXED PKI SECURITY MATCHERS RAILS: Unified with your normalized authorities array
                // ====================================================================
                .requestMatchers("/api/v1/master/governance/signing-keys/**").hasAnyAuthority(
                    "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN"
                )

                // 3. GOVERNANCE
                .requestMatchers("/api/v1/governance/**").hasAnyAuthority(
                    "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", 
                    "ADMIN", "ROLE_ADMIN", 
                    "DEVELOPER", "ROLE_DEVELOPER", "ROLE_EXTERNAL", "External"
                )

                // 4. EXTERNAL DATA PULL
                .requestMatchers("/api/v1/external/data-pull/**").hasAnyAuthority(
                    "External", "ROLE_EXTERNAL", 
                    "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", 
                    "ADMIN", "ROLE_ADMIN"
                )

                // 5. ADMIN PATHS
                .requestMatchers("/api/v1/admin/**").hasAnyAuthority(
                    "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN"
                )

                // 6. CATCH-ALL
                .anyRequest().authenticated()
            )
            
            // 7. JWT FILTER (Primary Auth)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            
            // 8. API KEY FILTER (Secondary Auth)
            .addFilterAfter((request, response, chain) -> {
                HttpServletRequest httpRequest = (HttpServletRequest) request;
                if (SecurityContextHolder.getContext().getAuthentication() == null) {
                    if (!"OPTIONS".equalsIgnoreCase(httpRequest.getMethod()) && !httpRequest.getRequestURI().contains("/auth/login")) {
                        apiKeyFilter(httpRequest);
                    }
                }
                chain.doFilter(request, response);
            }, JwtAuthenticationFilter.class);
            
        return http.build();
    }
    
    private void apiKeyFilter(HttpServletRequest request) {
        String apiKey = request.getHeader("X-API-KEY");
        if (apiKey != null && !apiKey.isEmpty()) {
            apiClientRepository.findByApiKey(apiKey)
                .filter(client -> Boolean.TRUE.equals(client.getIsActive()))
                .ifPresent(client -> {
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        client.getClientName(), 
                        null, 
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_EXTERNAL"))
                    );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
        }
    }
}