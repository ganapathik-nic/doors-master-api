package org.gepnic.doors.masterapi.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.gepnic.doors.masterapi.repository.UserRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class ManagerPlaneFilter extends OncePerRequestFilter {

    private final ManagerPlaneAccess managerPlaneAccess;
    private final PlaneRolePolicy planeRolePolicy;
    private final UserRepository userRepository;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // These endpoints must remain reachable when the browser carries a
        // stale session from the wrong plane, otherwise the login page cannot
        // obtain CSRF/CAPTCHA data or clear that session. Role/plane checks for
        // new sessions are enforced inside login and MFA verification.
        return path.equals("/api/v1/auth/csrf")
                || path.equals("/api/v1/auth/captcha")
                || path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/auth/mfa/verify")
                || path.equals("/api/v1/auth/logout");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        org.gepnic.doors.masterapi.model.User user = null;
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())) {
            user = userRepository.findByUsername(authentication.getName()).orElse(null);
            if (user == null || !planeRolePolicy.isRoleAllowed(PlaneHostResolver.resolve(request), user.getRole())) {
                deny(response, "DOORS-ROLE-PLANE-DENIED", "This account is not permitted on this portal");
                return;
            }
        }
        if (isPrivileged(authentication) && !managerPlaneAccess.isAllowed(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"code\":\"DOORS-MANAGER-PLANE-REQUIRED\",\"message\":\"Privileged access requires the NIC VPN manager portal\"}");
            return;
        }
        if (isDataManager(authentication)) {
            if (user == null || "REVOKED".equalsIgnoreCase(user.getVpnStatus())) {
                deny(response, "DOORS-VPN-ENTITLEMENT-REQUIRED", "VPN access has been revoked");
                return;
            }
        }
        if (user != null && user.getVpnIp() != null && !user.getVpnIp().isBlank()
                && !managerPlaneAccess.isVpnIpAllowed(request, user.getVpnIp())) {
            deny(response, "DOORS-IP-NOT-ALLOWED", "Current IP is not whitelisted for this account");
            return;
        }
        chain.doFilter(request, response);
    }

    private void deny(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    private boolean isDataManager(Authentication authentication) {
        if (authentication == null) return false;
        return authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority().toUpperCase(Locale.ROOT))
                .anyMatch(authority -> authority.equals("DATAMANAGER")
                        || authority.equals("ROLE_DATAMANAGER")
                        || authority.equals("ADMIN")
                        || authority.equals("ROLE_ADMIN"));
    }

    private boolean isPrivileged(Authentication authentication) {
        if (authentication == null) return false;
        return authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority().toUpperCase(Locale.ROOT))
                .anyMatch(authority -> authority.equals("DATAMANAGER")
                        || authority.equals("ROLE_DATAMANAGER")
                        || authority.equals("ADMIN")
                        || authority.equals("ROLE_ADMIN")
                        || authority.equals("SECURITYADMIN")
                        || authority.equals("ROLE_SECURITYADMIN"));
    }
}
