package org.gepnic.doors.masterapi.config;


import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequestWrapper;
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

        if (isPublicAuthEndpoint(uri)) {
            filterChain.doFilter(request, response);
            return;
        }

        String cookieToken = null;
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            cookieToken = readCookie(request, "DOORS_SESSION");
            if (cookieToken != null && !cookieToken.isBlank()) {
                authHeader = "Bearer " + cookieToken;
            }
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
                        if (!Boolean.TRUE.equals(user.getIsActive()) || !"ACTIVE".equalsIgnoreCase(user.getStatus())) {
                            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Account is inactive");
                            return;
                        }
                        rawRole = user.getRole();
                        String activeDbSid = user.getCurrentSessionId();

                        if (Boolean.TRUE.equals(user.getPasswordResetRequired())
                                && !uri.equals("/api/v1/auth/change-password")
                                && !uri.equals("/api/v1/auth/me")
                                && !uri.equals("/api/v1/auth/logout")) {
                            log.warn("DOORS-SECURITY: Password change required for user: {}", username);
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType("application/json");
                            response.getWriter().write(
                                "{\"error\":\"Password change required\",\"message\":\"Change the temporary password before continuing\"}"
                            );
                            return;
                        }

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
                            publishFailure(username, "Session no longer valid", request);
                            
                            response.setHeader("X-Session-Status", "INVALIDATED");
                            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Session is no longer valid. Please sign in again.");
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
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write(
                        "{\"error\":\"Unauthorized\",\"message\":\"Invalid or expired session\"}"
                    );
                    return;
                }
            } catch (Exception e) {
                log.error("DOORS-SECURITY: Token error: {}", e.getMessage());
                publishFailure("UNKNOWN", "Token verification failed", request);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write(
                    "{\"error\":\"Unauthorized\",\"message\":\"Invalid or expired session\"}"
                );
                return;
            }
        }
        
        HttpServletRequest requestForChain = cookieToken == null
                ? request
                : withAuthorizationHeader(request, "Bearer " + cookieToken);
        filterChain.doFilter(requestForChain, response);
    }

    private boolean isPublicAuthEndpoint(String uri) {
        return uri.equals("/api/v1/auth/login")
                || uri.equals("/api/v1/auth/register")
                || uri.equals("/api/v1/auth/captcha")
                || uri.equals("/api/v1/auth/csrf")
                || uri.equals("/api/v1/auth/mfa/verify")
                || uri.equals("/api/v1/auth/logout")
                || uri.equals("/api/auth/captcha");
    }

    private String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        return Arrays.stream(cookies)
                .filter(cookie -> name.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private HttpServletRequest withAuthorizationHeader(HttpServletRequest request, String value) {
        return new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                return "Authorization".equalsIgnoreCase(name) ? value : super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                return "Authorization".equalsIgnoreCase(name)
                        ? Collections.enumeration(List.of(value))
                        : super.getHeaders(name);
            }
        };
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
