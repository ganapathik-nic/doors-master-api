package org.gepnic.doors.masterapi.config;

import org.gepnic.doors.masterapi.service.SwaggerSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SwaggerSubscriberSessionTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private boolean request(String path, boolean valid) throws Exception {
        var service=mock(SwaggerSessionService.class);
        when(service.isActiveSession("scoped-test-session")).thenReturn(valid);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "subscriber",null,List.of(new SimpleGrantedAuthority("APIUSER"))));
        var request=new MockHttpServletRequest("GET",path);
        request.addHeader(SwaggerSessionAuthenticationFilter.SESSION_HEADER,"scoped-test-session");
        new SwaggerSessionAuthenticationFilter(service).doFilter(request,new MockHttpServletResponse(),new MockFilterChain());
        var auth=SecurityContextHolder.getContext().getAuthentication();
        assertEquals("subscriber",auth.getName());
        return auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_SWAGGER_SESSION"));
    }
    @Test void validScopedLaunchWorksAlongsidePortalCookie() throws Exception {
        assertTrue(request("/v3/api-docs",true));
        assertTrue(request("/api/v1/master/gateway/swagger-sessions/contract/example",true));
    }
    @Test void invalidLaunchAndNonSwaggerPathsNeverGainSwaggerAuthority() throws Exception {
        assertFalse(request("/v3/api-docs",false));
        assertFalse(request("/api/v1/master/api-subscriptions",true));
        assertFalse(request("/api/v1/reports/execute",true));
    }
}
