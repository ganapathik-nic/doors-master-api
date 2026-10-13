package org.gepnic.doors.masterapi.controller;

import org.gepnic.doors.masterapi.service.SecureDocumentationService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SecureDocumentationMediaTest {
    private final SecureDocumentationService service = new SecureDocumentationService();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SecureDocumentationController(service)).build();
    private UsernamePasswordAuthenticationToken auth(String role) {
        return new UsernamePasswordAuthenticationToken("media-test", null, List.of(new SimpleGrantedAuthority(role)));
    }

    @Test
    void videoIsDeliveredWithCorrectTypeAndSeekingSupport() throws Exception {
        var auth = auth("DATAVIEWER");
        byte[] bytes = service.resolve("doors-platform-promo", auth).resource().getInputStream().readAllBytes();
        var result = mvc.perform(get("/api/v1/documentation/documents/doors-platform-promo")
                .principal(auth).header("Range", "bytes=0-1023"))
                .andExpect(status().isPartialContent())
                .andExpect(content().contentType("video/mp4"))
                .andExpect(header().string("Content-Range", "bytes 0-1023/" + bytes.length))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        assertArrayEquals(java.util.Arrays.copyOfRange(bytes, 0, 1024), result.getResponse().getContentAsByteArray());
        int start = bytes.length - 512;
        var tail = mvc.perform(get("/api/v1/documentation/documents/doors-platform-promo")
                .principal(auth).header("Range", "bytes=" + start + "-"))
                .andExpect(status().isPartialContent()).andReturn();
        assertArrayEquals(java.util.Arrays.copyOfRange(bytes, start, bytes.length), tail.getResponse().getContentAsByteArray());
    }

    @Test
    void integrationPdfIsDeliveredUnchangedAndDeniedToUnassignedRoles() throws Exception {
        var auth = auth("APIUSER");
        var result = mvc.perform(get("/api/v1/documentation/documents/gepnic-sasci-integration").principal(auth))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff")).andReturn();
        assertArrayEquals(service.resolve("gepnic-sasci-integration", auth).resource().getInputStream().readAllBytes(),
                result.getResponse().getContentAsByteArray());
        mvc.perform(get("/api/v1/documentation/documents/gepnic-sasci-integration").principal(auth("DATAVIEWER")))
                .andExpect(status().isNotFound());
    }
}
