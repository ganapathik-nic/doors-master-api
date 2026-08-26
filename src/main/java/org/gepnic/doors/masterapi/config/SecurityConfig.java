package org.gepnic.doors.masterapi.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
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
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
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
    private final SwaggerSessionAuthenticationFilter swaggerSessionAuthFilter;
    private final ManagerPlaneFilter managerPlaneFilter;
    private final ApiClientRepository apiClientRepository;
    private final DoorsSecurityProperties doorsSecurityProperties;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        configuration.setAllowedOriginPatterns(doorsSecurityProperties.getAllowedOrigins());
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList(
                "Authorization", "Content-Type", "X-API-KEY", "X-DOORS-SWAGGER-SESSION",
                "X-XSRF-TOKEN", "Accept", "Origin"));
        configuration.setAllowCredentials(true);
        configuration.setExposedHeaders(Arrays.asList("X-API-KEY", "Authorization", "X-Session-Status"));
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }    
    
    @Bean 
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler =
                new CsrfTokenRequestAttributeHandler();

        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrfRequestHandler)
                .ignoringRequestMatchers(
                    "/api/v1/auth/login",
                    "/api/v1/auth/register",
                    "/api/v1/auth/logout",
                    "/api/v1/auth/mfa/verify",
                    "/api/v1/external/execute/**",
                    "/api/v1/master/gateway/handshake",
                    "/api/v1/master/gateway/orchestrate/**",
                    "/api/v1/master/gateway/documents/**",
                    "/api/v1/master/gateway/telemetry/**",
                    "/api/v1/master/gateway/swagger-sessions/exchange",
                    "/swagger/api/v1/master/gateway/swagger-sessions/exchange",
                    "/api/v1/master/reports/orchestrate/**"
                )
            )
            
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) 
            
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, authException) -> {
                    System.out.println(">>> SECURITY ENTRY POINT: Forbidden/Unauthorized access to: " + request.getRequestURI());
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\": \"Unauthorized\", \"message\": \"Authentication required\"}");
                })
                .accessDeniedHandler((request, response, denied) -> {
                    boolean csrfFailure = denied instanceof MissingCsrfTokenException
                            || denied instanceof InvalidCsrfTokenException;
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    if (csrfFailure) {
                        response.getWriter().write(
                            "{\"code\":\"DOORS-CSRF-INVALID\",\"message\":\"Security token expired. Retrying request.\"}"
                        );
                    } else {
                        response.getWriter().write(
                            "{\"code\":\"DOORS-AUTH-ACCESS-DENIED\",\"message\":\"Access denied\"}"
                        );
                    }
                })
            )
            .authorizeHttpRequests(auth -> auth
                // 1. PUBLIC & OPTIONS Whitelists
                .requestMatchers("/api/v1/master/gateway/.well-known/jwks.json").permitAll()
                .requestMatchers("/api/v1/master/gateway/api-clients/parse-certificate").hasAnyAuthority(
                    "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN"
                )
                .requestMatchers("/auth/bootstrap-hash").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(
                    "/api/v1/auth/login",
                    "/api/v1/auth/register",
                    "/api/v1/auth/captcha",
                    "/api/v1/auth/csrf",
                    "/api/v1/auth/mfa/verify",
                    "/api/v1/auth/logout",
                    "/api/auth/captcha",
                    "/error"
                ).permitAll()
                .requestMatchers(
                    "/api/v1/auth/me",
                    "/api/v1/auth/change-password"
                ).authenticated()
                .requestMatchers("/api/v1/auth/list/active").hasAnyAuthority(
                    "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN"
                )
                .requestMatchers("/api/v1/master/aira/**").hasAnyAuthority(
                    "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER",
                    "ADMIN", "ROLE_ADMIN"
                )
                .requestMatchers("/api/v1/master/reports/orchestrate/**").permitAll()
                .requestMatchers("/api/v1/master/gateway/orchestrate/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/master/gateway/documents/**")
                    .hasAuthority("ROLE_API_CLIENT")
                .requestMatchers("/api/v1/master/gateway/telemetry/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/master/gateway/handshake")
                    .hasAuthority("ROLE_API_CLIENT")
                .requestMatchers(HttpMethod.POST,
                    "/api/v1/master/gateway/swagger-sessions/exchange",
                    "/swagger/api/v1/master/gateway/swagger-sessions/exchange"
                ).permitAll()
                .requestMatchers(HttpMethod.GET,
                    "/swagger/session/exchange",
                    "/session/exchange"
                ).permitAll()

                .requestMatchers(
                    "/v3/api-docs/**",
                    "/v3/api-docs.yaml",
                    "/swagger-resources/**",
                    "/api/v1/master/gateway/swagger-sessions/contract/**",
                    "/swagger/api/v1/master/gateway/swagger-sessions/contract/**",
                    "/swagger/session/contract/**",
                    "/session/contract/**"
                ).hasAuthority("ROLE_SWAGGER_SESSION")

                .requestMatchers(HttpMethod.GET, "/api/v1/master/gateway/template-contracts/**")
                .hasAnyAuthority(
                    "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER",
                    "ADMIN", "ROLE_ADMIN"
                )

                // 🚀 FULL SWAGGER UI & OPENAPI WHITELIST (Covers standard & custom prefixes)
                .requestMatchers(
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/swagger/doors-swagger.html",
                    "/doors-swagger.html",
                    "/swagger-autofill.js",
                    "/doors-swagger-bootstrap.js",
                    "/doors-swagger-forge.js",
                    "/swagger/doors-swagger-forge.js",
                    "/doors-swagger-template-catalogue.js",
                    "/swagger/doors-swagger-template-catalogue.js",
                    "/doors-swagger-crypto.js",
                    "/doors-template-catalogue.js",
                    "/forge-1.3.2.min.js",
                    "/swagger/forge-1.3.2.min.js",
                    "/vendor/**",
                    "/webjars/**"
                ).permitAll()

                // Portal administration and governance.
                .requestMatchers("/api/v1/master/governance/signing-keys/**").hasAnyAuthority(
                    "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN"
                )

                .requestMatchers("/api/v1/admin/users/**").hasAnyAuthority(
                    "SecurityAdmin", "SECURITYADMIN", "ROLE_SECURITYADMIN",
                    "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN"
                )

                .requestMatchers("/api/v1/admin/**").hasAnyAuthority(
                    "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN"
                )

                .requestMatchers(HttpMethod.GET, "/api/v1/governance/requests/*/download-evidence")
                    .hasAnyAuthority(
                        "Developer", "DEVELOPER", "ROLE_DEVELOPER",
                        "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN"
                    )
                .requestMatchers(HttpMethod.POST, "/api/v1/governance/requests/submit")
                    .hasAnyAuthority(
                        "External", "EXTERNAL", "ROLE_EXTERNAL",
                        "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN"
                    )
                .requestMatchers("/api/v1/governance/**").hasAnyAuthority(
                    "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN"
                )

                .requestMatchers(HttpMethod.POST, "/api/v1/external/data-pull/submit")
                    .hasAnyAuthority(
                        "External", "EXTERNAL", "ROLE_EXTERNAL",
                        "ApiUser", "APIUSER", "ROLE_APIUSER"
                    )
                .requestMatchers(HttpMethod.GET, "/api/v1/external/data-pull/my-list")
                    .hasAnyAuthority(
                        "External", "EXTERNAL", "ROLE_EXTERNAL",
                        "ApiUser", "APIUSER", "ROLE_APIUSER"
                    )
                .requestMatchers("/api/v1/external/data-pull/**").hasAnyAuthority(
                    "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN"
                )
                .requestMatchers("/api/v1/external/execute/**").hasAuthority("ROLE_API_CLIENT")
                .requestMatchers(HttpMethod.GET, "/api/v1/external/api-user/clients")
                    .hasAnyAuthority("ApiUser", "APIUSER", "ROLE_APIUSER")
                .requestMatchers(HttpMethod.POST, "/api/v1/external/api-user/swagger-sessions")
                    .hasAnyAuthority("ApiUser", "APIUSER", "ROLE_APIUSER")
                .requestMatchers("/api/v1/external/**").denyAll()

                .requestMatchers(HttpMethod.GET, "/api/v1/master/agents/list/active")
                    .hasAnyAuthority(
                        "External", "EXTERNAL", "ROLE_EXTERNAL",
                        "ApiUser", "APIUSER", "ROLE_APIUSER",
                        "Developer", "DEVELOPER", "ROLE_DEVELOPER",
                        "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN"
                    )
                .requestMatchers(HttpMethod.GET, "/api/v1/master/categories/list")
                    .hasAnyAuthority(
                        "Developer", "DEVELOPER", "ROLE_DEVELOPER",
                        "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN"
                    )
                .requestMatchers("/api/v1/master/categories/**").hasAnyAuthority(
                    "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN"
                )

                .requestMatchers(HttpMethod.GET, "/api/v1/master/governance/requests/approved")
                    .hasAnyAuthority(
                        "Developer", "DEVELOPER", "ROLE_DEVELOPER",
                        "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN"
                    )
                .requestMatchers(HttpMethod.POST, "/api/v1/master/governance/propose")
                    .hasAnyAuthority(
                        "Developer", "DEVELOPER", "ROLE_DEVELOPER",
                        "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN"
                    )
                .requestMatchers("/api/v1/master/governance/**").hasAnyAuthority(
                    "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN"
                )

                .requestMatchers(HttpMethod.POST, "/api/v1/master/templates/submit")
                    .hasAnyAuthority(
                        "Developer", "DEVELOPER", "ROLE_DEVELOPER",
                        "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN"
                    )
                .requestMatchers(HttpMethod.POST, "/api/v1/master/templates/test-dry-run")
                    .hasAnyAuthority(
                        "Developer", "DEVELOPER", "ROLE_DEVELOPER",
                        "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN"
                    )
                .requestMatchers(HttpMethod.GET, "/api/v1/master/templates/list/my-submissions")
                    .hasAnyAuthority(
                        "Developer", "DEVELOPER", "ROLE_DEVELOPER",
                        "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN"
                    )
                .requestMatchers(HttpMethod.GET,
                    "/api/v1/master/templates/by-request/*",
                    "/api/v1/master/templates/requests/details/*")
                    .hasAnyAuthority(
                        "Developer", "DEVELOPER", "ROLE_DEVELOPER",
                        "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN"
                    )
                .requestMatchers("/api/v1/master/templates/**").hasAnyAuthority(
                    "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN"
                )

                .requestMatchers(HttpMethod.GET, "/api/v1/master/dashboard/user/stats")
                    .hasAnyAuthority(
                        "External", "EXTERNAL", "ROLE_EXTERNAL",
                        "DataViewer", "DATAVIEWER", "ROLE_DATAVIEWER",
                        "Developer", "DEVELOPER", "ROLE_DEVELOPER",
                        "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN"
                    )
                .requestMatchers("/api/v1/reports/**").hasAnyAuthority(
                    "External", "EXTERNAL", "ROLE_EXTERNAL",
                    "DataViewer", "DATAVIEWER", "ROLE_DATAVIEWER",
                    "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN"
                )

                // Remaining manager-plane endpoints are never available merely
                // because a portal user is authenticated.
                .requestMatchers("/api/v1/master/**").hasAnyAuthority(
                    "DataManager", "DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN"
                )

                // Unknown endpoints are denied by default.
                .anyRequest().denyAll()
            )
            
            // 7. JWT FILTER (Primary Auth)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(managerPlaneFilter, JwtAuthenticationFilter.class)
            .addFilterAfter(swaggerSessionAuthFilter, JwtAuthenticationFilter.class)
            
            // 8. API KEY FILTER (Secondary Auth)
            .addFilterAfter((request, response, chain) -> {
                HttpServletRequest httpRequest = (HttpServletRequest) request;
                if (SecurityContextHolder.getContext().getAuthentication() == null) {
                    if (!"OPTIONS".equalsIgnoreCase(httpRequest.getMethod())
                            && !httpRequest.getRequestURI().contains("/auth/login")) {
                        apiKeyFilter(httpRequest);
                    }
                }
                chain.doFilter(request, response);
            }, SwaggerSessionAuthenticationFilter.class);
            
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
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_API_CLIENT"))
                    );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
        }
    }
}
