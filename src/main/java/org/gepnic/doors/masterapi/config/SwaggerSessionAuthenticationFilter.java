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
import java.util.ArrayList;

@Component
@RequiredArgsConstructor
public class SwaggerSessionAuthenticationFilter extends OncePerRequestFilter {

    public static final String SESSION_HEADER = "X-DOORS-SWAGGER-SESSION";

    private final SwaggerSessionService swaggerSessionService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(SESSION_HEADER);
        var existing = SecurityContextHolder.getContext().getAuthentication();
        String path = request.getRequestURI();
        boolean swaggerDocument = path.startsWith("/v3/api-docs") || path.startsWith("/swagger/")
                || path.startsWith("/swagger-resources/") || path.startsWith("/api/v1/master/gateway/swagger-sessions/contract/");
        boolean apiSubscriber = existing != null && existing.isAuthenticated() && existing.getAuthorities().stream()
                .anyMatch(a -> List.of("ApiUser", "APIUSER", "ROLE_APIUSER").contains(a.getAuthority()));
        if ((existing == null || (apiSubscriber && swaggerDocument)) && swaggerSessionService.isActiveSession(token)) {
            if (existing != null) {
                // A portal cookie must not hide a valid, scoped Swagger launch token.
                // Keep subscriber identity so plane/role checks still apply.
                var authorities = new ArrayList<org.springframework.security.core.GrantedAuthority>(existing.getAuthorities());
                authorities.add(new SimpleGrantedAuthority("ROLE_SWAGGER_SESSION"));
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        existing.getPrincipal(), existing.getCredentials(), authorities));
            } else {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            "swagger-session", null,
                            List.of(new SimpleGrantedAuthority("ROLE_SWAGGER_SESSION"))
                    )
            );
            }
        }
        chain.doFilter(request, response);
    }
}
