package org.gepnic.doors.masterapi.service;

import org.gepnic.doors.masterapi.model.ExternalRequest;
import org.gepnic.doors.masterapi.repository.DataPullRequestRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MyDataRequestDetailsTest {
    private final DataPullRequestRepository repository = mock(DataPullRequestRepository.class);
    private final DataPullService service = new DataPullService();

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "repository", repository);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "owner@example.test", null, List.of(new SimpleGrantedAuthority("ROLE_EXTERNAL"))));
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test void ownerReceivesExplicitResponseWithoutAttachmentBytes() throws Exception {
        var request = ExternalRequest.builder().id(10L).requestedBy("owner@example.test")
                .requestTitle("<img src=x onerror=alert(1)>").attachmentData(new byte[]{1, 2})
                .approvedBy("private-reviewer").build();
        when(repository.findByIdAndRequestedBy(10L, "owner@example.test")).thenReturn(Optional.of(request));
        var result = service.getMyRequest(10L);
        assertEquals(10L, result.requestId());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
        assertFalse(json.contains("attachmentData"));
        assertFalse(json.contains("approvedBy"));
        verify(repository).findByIdAndRequestedBy(10L, "owner@example.test");
        verifyNoMoreInteractions(repository);
    }
    @Test void foreignAndMissingRecordsHaveIdenticalDenial() {
        when(repository.findByIdAndRequestedBy(anyLong(), anyString())).thenReturn(Optional.empty());
        var foreign = assertThrows(SecurityException.class, () -> service.getMyRequest(4L));
        var missing = assertThrows(SecurityException.class, () -> service.getMyRequest(999999L));
        assertEquals(foreign.getMessage(), missing.getMessage());
        verify(repository).findByIdAndRequestedBy(4L, "owner@example.test");
    }
    @Test void historyResponseExcludesEntityOnlyFields() throws Exception {
        when(repository.findByRequestedByOrderByCreatedAtDesc("owner@example.test"))
                .thenReturn(List.of(ExternalRequest.builder().id(10L).approvedBy("private-reviewer")
                        .attachmentData(new byte[]{1, 2}).build()));
        var result = service.getRequestsByUser("owner@example.test");
        assertEquals(10L, result.get(0).requestId());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
        assertFalse(json.contains("attachmentData"));
        assertFalse(json.contains("approvedBy"));
        assertFalse(json.contains("requestedBy"));
    }
    @Test void privilegedRoleDoesNotBypassOwnerFilter() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "manager@example.test", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        when(repository.findByIdAndRequestedBy(4L, "manager@example.test")).thenReturn(Optional.empty());
        assertThrows(SecurityException.class, () -> service.getMyRequest(4L));
        verify(repository).findByIdAndRequestedBy(4L, "manager@example.test");
    }
    @Test void missingAuthenticationDoesNotQueryDatabase() {
        SecurityContextHolder.clearContext();
        assertThrows(SecurityException.class, () -> service.getMyRequest(10L));
        verifyNoInteractions(repository);
    }
    @Test void anonymousAuthenticationDoesNotQueryDatabase() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "test", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThrows(SecurityException.class, () -> service.getMyRequest(10L));
        verifyNoInteractions(repository);
    }
    @Test void invalidIdsDoNotQueryDatabase() {
        assertThrows(SecurityException.class, () -> service.getMyRequest(0L));
        assertThrows(SecurityException.class, () -> service.getMyRequest(-1L));
        assertThrows(SecurityException.class, () -> service.getMyRequest(null));
        verifyNoInteractions(repository);
    }
}
