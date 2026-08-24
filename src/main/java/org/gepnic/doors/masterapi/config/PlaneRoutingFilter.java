package org.gepnic.doors.masterapi.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class PlaneRoutingFilter extends OncePerRequestFilter {

    private final PlaneProperties properties;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !properties.isEnforced()
                || "OPTIONS".equalsIgnoreCase(request.getMethod())
                || !(path.startsWith("/api/")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger")
                || path.startsWith("/doors-swagger"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Plane plane = resolvePlane(PlaneHostResolver.resolve(request));
        String path = request.getRequestURI();

        if (plane == Plane.UNKNOWN) {
            deny(response, 421,
                    "DOORS-PLANE-UNKNOWN", "The request host is not assigned to a DOORS plane");
            return;
        }
        if (!isAllowed(plane, path)) {
            deny(response, HttpServletResponse.SC_FORBIDDEN,
                    "DOORS-PLANE-ROUTE-DENIED", "The endpoint is not exposed on the " + plane.name().toLowerCase(Locale.ROOT) + " plane");
            return;
        }

        response.setHeader("X-DOORS-PLANE", plane.name().toLowerCase(Locale.ROOT));
        chain.doFilter(request, response);
    }

    Plane resolvePlane(String host) {
        String normalized = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        if (matches(properties.getAdminHosts(), normalized)) return Plane.ADMIN;
        if (matches(properties.getExternalHosts(), normalized)) return Plane.EXTERNAL;
        if (matches(properties.getApiHosts(), normalized)) return Plane.API;
        return Plane.UNKNOWN;
    }

    boolean isAllowed(Plane plane, String path) {
        return switch (plane) {
            case ADMIN -> startsWithAny(path,
                    "/api/v1/auth/", "/api/v1/admin/",
                    "/api/v1/master/gateway/api-clients/",
                    "/api/v1/master/", "/api/v1/governance/", "/api/v1/reports/")
                    && (!path.startsWith("/api/v1/master/gateway/")
                    || path.startsWith("/api/v1/master/gateway/api-clients/"));
            case EXTERNAL -> !path.startsWith("/api/v1/external/execute/") && startsWithAny(path,
                    "/api/v1/auth/", "/api/v1/external/",
                    "/api/v1/governance/requests", "/api/v1/reports/");
            case API -> startsWithAny(path,
                    "/api/v1/master/gateway/", "/api/v1/external/execute/",
                    "/v3/api-docs", "/swagger", "/doors-swagger");
            case UNKNOWN -> false;
        };
    }

    private boolean matches(List<String> configuredHosts, String host) {
        return configuredHosts.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(Predicate.isEqual(host));
    }

    private boolean startsWithAny(String path, String... prefixes) {
        for (String prefix : prefixes) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    private void deny(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    enum Plane {
        ADMIN, EXTERNAL, API, UNKNOWN
    }
}
