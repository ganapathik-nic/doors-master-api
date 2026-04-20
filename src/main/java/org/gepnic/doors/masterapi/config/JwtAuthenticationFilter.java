package org.gepnic.doors.masterapi.config;


import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 🛡️ Critical Imports for the new logic
import org.gepnic.doors.masterapi.repository.UserRepository;
import org.gepnic.doors.masterapi.model.User;

import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.*; // Covers List, Map, Optional, ArrayList, Arrays

 @Slf4j
@Component
@RequiredArgsConstructor 
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils; 
    private final AuthenticationEventPublisher eventPublisher;
    private final UserRepository userRepository; // 🛡️ Added missing repository

  @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        String uri = request.getRequestURI();

        if (uri.contains("/api/v1/auth/")) {
            filterChain.doFilter(request, response);
            return;
        }

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();

            try {
                // 🛡️ 1. Decrypt and Parse JWE
                Map<String, Object> claims = jwtUtils.parseToken(token);

                if (claims != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    String username = (String) claims.get("sub");
                    String rawRole = (String) claims.get("role");
                    String tokenSid = (String) claims.get("sid");

                    // 🛡️ 2. Single Session Validation (Replaced Lambda with if/else)
                    Optional<User> userOpt = userRepository.findByUsername(username);

                    if (userOpt.isPresent()) {
                        User user = userOpt.get();
                        String activeDbSid = user.getCurrentSessionId();

                        if (activeDbSid != null && activeDbSid.equals(tokenSid)) {
                            // ✅ Success logic
                            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                            if (rawRole != null) {
                                Arrays.stream(rawRole.split(","))
                                    .map(String::trim)
                                    .forEach(r -> {
                                        authorities.add(new SimpleGrantedAuthority(r.toUpperCase()));
                                        authorities.add(new SimpleGrantedAuthority("ROLE_" + r.toUpperCase()));
                                    });
                            }

                            UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                                    username, null, authorities
                            );
                            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                            SecurityContextHolder.getContext().setAuthentication(authToken);
                        } else {
                            // ❌ Failure: Session Mismatch
                            log.warn("DOORS-SECURITY: Session mismatch for user: {}", username);
                            publishFailure(username, "Multiple session detected / Session Expired", request);
                            
                            response.setHeader("X-Session-Status", "CONFLICT");
                            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Multiple login detected.");
                            return; // Stop filter chain
                        }
                    } else {
                        // ❌ Failure: User no longer exists
                        publishFailure(username, "User context lost", request);
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "User context lost.");
                        return;
                    }
                } else if (claims == null) {
                    publishFailure("UNKNOWN", "Invalid or Expired Security Context", request);
                }
            } catch (Exception e) {
                log.error("DOORS-SECURITY: Token error: {}", e.getMessage());
                publishFailure("UNKNOWN", "Token verification failed", request);
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