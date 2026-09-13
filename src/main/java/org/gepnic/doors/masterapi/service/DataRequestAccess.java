package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.repository.ExternalRequestRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DataRequestAccess {
    private final ExternalRequestRepository requests;
    public void requireRead(Long id) {
        var request = requests.findById(id).orElseThrow(() -> new SecurityException("Request access denied"));
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new SecurityException("Authentication required");
        var roles = auth.getAuthorities().stream().map(a -> a.getAuthority().toUpperCase(java.util.Locale.ROOT)).toList();
        if (roles.stream().anyMatch(Set.of("DATAMANAGER", "ROLE_DATAMANAGER", "ADMIN", "ROLE_ADMIN")::contains)) return;
        if (roles.stream().anyMatch(Set.of("DEVELOPER", "ROLE_DEVELOPER")::contains)
                && Set.of("APPROVED", "SQL-SUBMITTED", "SQL-APPROVED").contains(
                    String.valueOf(request.getStatus()).toUpperCase(java.util.Locale.ROOT))) return;
        if (auth.getName().equals(request.getRequestedBy())) return;
        throw new SecurityException("Request access denied");
    }
}
