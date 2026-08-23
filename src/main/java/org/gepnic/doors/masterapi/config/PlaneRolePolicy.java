package org.gepnic.doors.masterapi.config;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class PlaneRolePolicy {

    private static final Set<String> ADMIN_ROLES = Set.of("DATAMANAGER", "ADMIN", "SECURITYADMIN", "DEVELOPER");
    private static final Set<String> EXTERNAL_ROLES = Set.of("EXTERNAL", "DATAVIEWER");

    private final PlaneProperties properties;

    public boolean isRoleAllowed(String host, String role) {
        if (!properties.isEnforced()) return true;

        String normalizedHost = normalize(host);
        String normalizedRole = normalizeRole(role);

        if (contains(properties.getAdminHosts(), normalizedHost)) {
            return ADMIN_ROLES.contains(normalizedRole);
        }
        if (contains(properties.getExternalHosts(), normalizedHost)) {
            return EXTERNAL_ROLES.contains(normalizedRole);
        }

        // The API plane and unknown hosts never accept interactive users.
        return false;
    }

    private boolean contains(List<String> hosts, String host) {
        return hosts.stream().map(this::normalize).anyMatch(host::equals);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeRole(String role) {
        return role == null ? "" : role.trim()
                .replaceFirst("(?i)^ROLE_", "")
                .replaceAll("[\\s_-]", "")
                .toUpperCase(Locale.ROOT);
    }
}
