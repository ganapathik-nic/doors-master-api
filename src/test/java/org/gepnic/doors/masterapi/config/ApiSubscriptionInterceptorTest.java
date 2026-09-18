package org.gepnic.doors.masterapi.config;

import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.exception.*;
import org.gepnic.doors.masterapi.repository.ApiClientRepository;
import org.gepnic.doors.masterapi.service.ApiSubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiSubscriptionInterceptorTest {
    @RestController static class Probe {
        @RequestMapping({"/api/v1/master/gateway/handshake", "/api/v1/master/gateway/orchestrate/query",
                "/api/v1/master/gateway/documents/service/download", "/api/v1/master/gateway/documents/downloads/id/receipt",
                "/api/v1/master/gateway/telemetry/report-fault", "/api/v1/master/reports/orchestrate/query",
                "/api/v1/external/execute/1", "/api/v1/reports/execute", "/api/v1/reports/exports/id/download"})
        public Map<String,String> execute() { return Map.of("result","ok"); }
    }
    @Test void allMachineRoutesDeniedEvenWithExistingAuthenticationAndReportsPreserved() throws Exception {
        var clients=mock(ApiClientRepository.class); var service=mock(ApiSubscriptionService.class);
        var client=new ApiClient();client.setClientId(7L);
        when(clients.findByApiKey("test-key")).thenReturn(Optional.of(client));
        doThrow(new DoorsApiException(HttpStatus.FORBIDDEN,"DOORS-SERVICE-DISABLED","api-subscription",
                "API subscription access denied","DOORS API service is disabled.",false,Map.of())).when(service).enforce(7L);
        var mvc=MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new GlobalExceptionHandler())
                .addMappedInterceptors(MachineApiRoutes.PATTERNS,new ApiSubscriptionInterceptor(clients,service)).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("swagger-session",null,List.of()));
        try {
            for(String path: List.of("/api/v1/master/gateway/handshake","/api/v1/master/gateway/orchestrate/query",
                    "/api/v1/master/gateway/documents/service/download","/api/v1/master/gateway/documents/downloads/id/receipt",
                    "/api/v1/master/gateway/telemetry/report-fault","/api/v1/master/reports/orchestrate/query","/api/v1/external/execute/1")) {
                mvc.perform(post(path).header("X-API-KEY","test-key")).andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("DOORS-SERVICE-DISABLED"));
            }
            mvc.perform(post("/api/v1/reports/execute")).andExpect(status().isOk());
            mvc.perform(get("/api/v1/reports/exports/id/download")).andExpect(status().isOk());
            verify(service,times(7)).enforce(7L);
        } finally { SecurityContextHolder.clearContext(); }
    }
    @Test void missingKeyCannotUseSessionToBypassClientPolicy() {
        var clients=mock(ApiClientRepository.class);var service=mock(ApiSubscriptionService.class);
        var interceptor=new ApiSubscriptionInterceptor(clients,service);
        assertThrows(DoorsApiException.class, () -> interceptor.preHandle(new MockHttpServletRequest("POST","/api/v1/master/gateway/handshake"), new MockHttpServletResponse(),new Object()));
        verifyNoInteractions(service);
    }
    @Test void machineRouteInventoryExcludesPortalAndPublicMetadata() {
        assertFalse(MachineApiRoutes.matches("/api/v1/reports/execute"));
        assertFalse(MachineApiRoutes.matches("/api/v1/external/api-user/subscription"));
        assertFalse(MachineApiRoutes.matches("/api/v1/master/gateway/.well-known/jwks.json"));
        assertTrue(MachineApiRoutes.matches("/api/v1/master/gateway/documents/service/download"));
    }
}
