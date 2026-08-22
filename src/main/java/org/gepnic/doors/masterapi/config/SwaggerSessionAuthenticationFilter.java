package org.gepnic.doors.masterapi.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.service.SwaggerSessionService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SwaggerSessionAuthenticationFilter extends OncePerRequestFilter {

    public static final String SESSION_HEADER = "X-DOORS-SWAGGER-SESSION";

    private final SwaggerSessionService swaggerSessionService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(SESSION_HEADER);
        if (SecurityContextHolder.getContext().getAuthentication() == null
                && swaggerSessionService.isActiveSession(token)) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            "swagger-session", null,
                            List.of(new SimpleGrantedAuthority("ROLE_SWAGGER_SESSION"))
                    )
            );
        }
        chain.doFilter(request, response);
    }
}
