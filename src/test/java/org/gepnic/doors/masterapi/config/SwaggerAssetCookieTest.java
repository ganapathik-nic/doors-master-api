package org.gepnic.doors.masterapi.config;

import jakarta.servlet.http.Cookie;
import org.gepnic.doors.masterapi.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class SwaggerAssetCookieTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void publicSwaggerAssetsIgnorePortalCookieAcrossProxyMounts() throws Exception {
        for (String path : new String[]{"/doors-swagger.html", "/swagger/doors-swagger.html",
                "/doors-swagger-bootstrap.js", "/swagger/vendor/swagger-ui-bundle.js"}) {
            var jwt = mock(JwtUtils.class);
            var request = new MockHttpServletRequest("GET", path);
            request.setCookies(new Cookie("DOORS_SESSION", "admin-session"));
            new JwtAuthenticationFilter(jwt, mock(AuthenticationEventPublisher.class), mock(UserRepository.class))
                    .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
            verifyNoInteractions(jwt);
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }
    }

    @Test void apiAndOpenApiRequestsStillValidatePortalCookies() throws Exception {
        for (String path : new String[]{"/api/v1/admin/swagger-sessions", "/v3/api-docs",
                "/swagger/api/v1/master/gateway/orchestrate/example"}) {
            var jwt = mock(JwtUtils.class);
            var request = new MockHttpServletRequest("GET", path);
            request.setCookies(new Cookie("DOORS_SESSION", "portal-session"));
            new JwtAuthenticationFilter(jwt, mock(AuthenticationEventPublisher.class), mock(UserRepository.class))
                    .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
            verify(jwt).parseToken("portal-session");
        }
    }

    @Test void explicitBearerOnSwaggerIsStillValidated() throws Exception {
        var jwt = mock(JwtUtils.class);
        var request = new MockHttpServletRequest("GET", "/doors-swagger.html");
        request.addHeader("Authorization", "Bearer explicit-token");
        new JwtAuthenticationFilter(jwt, mock(AuthenticationEventPublisher.class), mock(UserRepository.class))
                .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        verify(jwt).parseToken("explicit-token");
    }
}
