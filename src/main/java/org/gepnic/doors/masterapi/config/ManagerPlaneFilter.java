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
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (isPrivileged(authentication) && !managerPlaneAccess.isAllowed(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"code\":\"DOORS-MANAGER-PLANE-REQUIRED\",\"message\":\"Privileged access requires the NIC VPN manager portal\"}");
            return;
        }
        if (isDataManager(authentication)) {
            var user = userRepository.findByUsername(authentication.getName()).orElse(null);
            if (user == null || "REVOKED".equalsIgnoreCase(user.getVpnStatus())) {
                deny(response, "DOORS-VPN-ENTITLEMENT-REQUIRED", "VPN access has been revoked");
                return;
            }
            if ("CONFIRMED".equalsIgnoreCase(user.getVpnStatus())
                    && !managerPlaneAccess.isVpnIpAllowed(request, user.getVpnIp())) {
                deny(response, "DOORS-VPN-IP-NOT-ALLOWED", "Current VPN IP is not whitelisted for this account");
                return;
            }
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
