package org.gepnic.doors.masterapi.controller;

import org.gepnic.doors.masterapi.service.AiraChatHistoryService;
import org.gepnic.doors.masterapi.service.AiraService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiraTraceabilityTest {
    @Test void chatCreatesServerTraceForAuditAndResponse() {
        AiraService service = mock(AiraService.class);
        AiraChatHistoryService history = mock(AiraChatHistoryService.class);
        Authentication auth = auth();
        when(service.chat(eq("manager"), anySet(), eq("Explain planes"), anyString(), anyString(), eq(false)))
                .thenReturn(new HashMap<>(Map.of("answer", "Admin, External, API")));
        when(history.record(anyString(), any(), anyString(), anyMap())).thenReturn(Map.of());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-ID", "ATTACKER_CONTROLLED");
        assertEquals(200, new AIraController(service, history)
                .chat(Map.of("prompt", "Explain planes"), auth, request, response).getStatusCode().value());
        String traceId = response.getHeader("X-Trace-ID");
        assertNotNull(traceId);
        assertTrue(traceId.matches("DOORS-TRC-[A-F0-9]{8}"));
        verify(service).chat(eq("manager"), anySet(), eq("Explain planes"), eq(traceId), anyString(), eq(false));
    }

    @Test void refinementFailureKeepsTheSameTraceInAuditAndResponse() {
        AiraService service = mock(AiraService.class);
        AiraChatHistoryService history = mock(AiraChatHistoryService.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(service.chat(eq("manager"), anySet(), eq("Explain planes"), anyString(), anyString(), eq(true)))
                .thenThrow(new IllegalStateException("model unavailable"));
        assertEquals(503, new AIraController(service, history)
                .refine(Map.of("prompt", "Explain planes"), auth(), new MockHttpServletRequest(), response)
                .getStatusCode().value());
        String traceId = response.getHeader("X-Trace-ID");
        assertNotNull(traceId);
        assertTrue(traceId.matches("DOORS-TRC-[A-F0-9]{8}"));
        verify(service).chat(eq("manager"), anySet(), eq("Explain planes"), eq(traceId), anyString(), eq(true));
    }

    private Authentication auth() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("manager");
        doReturn(List.of(new SimpleGrantedAuthority("DATAMANAGER"))).when(auth).getAuthorities();
        return auth;
    }
}
