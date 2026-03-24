package org.gepnic.doors.masterapi.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor 
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils; 
    private final AuthenticationEventPublisher eventPublisher;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        String uri = request.getRequestURI();

        // 1. Skip filter for public auth endpoints
        if (uri.contains("/api/v1/auth/")) {
            filterChain.doFilter(request, response);
            return;
        }

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();

            try {
                if (jwtUtils.validateToken(token)) {
                    String username = jwtUtils.getUsernameFromToken(token);
                    String rawRole = jwtUtils.getRoleFromToken(token); // e.g., "Developer"

                    if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                        
                        // 🚀 FIX: Normalize Case and Add Role Prefixes
                        // This handles "Developer" -> "DEVELOPER" AND "ROLE_DEVELOPER"
                        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                        
                        if (rawRole != null && !rawRole.isEmpty()) {
                            Arrays.stream(rawRole.split(","))
                                .map(String::trim)
                                .filter(r -> !r.isEmpty())
                                .forEach(role -> {
                                    String upperRole = role.toUpperCase();
                                    // Add literal: "DEVELOPER"
                                    authorities.add(new SimpleGrantedAuthority(upperRole));
                                    // Add prefixed: "ROLE_DEVELOPER"
                                    if (!upperRole.startsWith("ROLE_")) {
                                        authorities.add(new SimpleGrantedAuthority("ROLE_" + upperRole));
                                    }
                                });
                        }

                        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                                username, null, authorities
                        );
                        
                        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authToken);
                        
                        log.debug("DOORS-SECURITY: Authenticated {} with authorities {}", username, authorities);
                    }
                } else if (uri.contains("/api/")) {
                    publishFailure(jwtUtils.getUsernameFromToken(token), "Expired or Invalid Token", request);
                }
            } catch (Exception e) {
                log.error("DOORS-SECURITY: JWT validation error: {}", e.getMessage());
                publishFailure("UNKNOWN", e.getMessage(), request);
            }
        }
        
        filterChain.doFilter(request, response);
    }

    private void publishFailure(String user, String message, HttpServletRequest request) {
        if (request.getAttribute("ALREADY_LOGGED_FAILURE") == null) {
            eventPublisher.publishAuthenticationFailure(
                new BadCredentialsException(message),
                new UsernamePasswordAuthenticationToken(user, null)
            );
            request.setAttribute("ALREADY_LOGGED_FAILURE", true);
        }
    }
}