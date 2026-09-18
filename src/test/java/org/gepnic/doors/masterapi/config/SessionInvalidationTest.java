package org.gepnic.doors.masterapi.config;

import org.gepnic.doors.masterapi.model.User;
import org.gepnic.doors.masterapi.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Map;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionInvalidationTest {
    @Test void loggedOutCookieIsRejectedWithoutClaimingAnotherLogin() throws Exception {
        var jwt = mock(JwtUtils.class);
        var users = mock(UserRepository.class);
        var events = mock(AuthenticationEventPublisher.class);
        var user = new User();
        user.setUsername("owner"); user.setIsActive(true); user.setStatus("ACTIVE"); user.setRole("External");
        user.setCurrentSessionId(null);
        user.setPasswordResetRequired(false);
        when(users.findByUsername("owner")).thenReturn(Optional.of(user));
        when(jwt.parseToken("old-test-token")).thenReturn(Map.of("sub", "owner", "role", "External", "sid", "old"));
        var request = new MockHttpServletRequest("GET", "/api/v1/external/data-pull/my-requests/10");
        request.addHeader("Authorization", "Bearer old-test-token");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        SecurityContextHolder.clearContext();
        try {
            new JwtAuthenticationFilter(jwt, events, users).doFilter(request, response, chain);
            assertEquals(401, response.getStatus());
            assertEquals("INVALIDATED", response.getHeader("X-Session-Status"));
            assertFalse(response.getErrorMessage().contains("Multiple login"));
            assertNull(chain.getRequest());
        } finally { SecurityContextHolder.clearContext(); }
    }
}
