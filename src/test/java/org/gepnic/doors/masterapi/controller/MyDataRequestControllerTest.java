package org.gepnic.doors.masterapi.controller;

import org.gepnic.doors.masterapi.dto.MyDataRequestDetails;
import org.gepnic.doors.masterapi.exception.GlobalExceptionHandler;
import org.gepnic.doors.masterapi.service.DataPullService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MyDataRequestControllerTest {
    @Test void detailContractIgnoresUsernameAndMapsDenialTo403() throws Exception {
        var service = mock(DataPullService.class);
        var controller = new DataPullController();
        ReflectionTestUtils.setField(controller, "service", service);
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(service.getMyRequest(10L)).thenReturn(new MyDataRequestDetails(
                10L, "My request", "agent", "reason", null, "SUBMITTED", null, null, null, null, null));
        when(service.getMyRequest(4L)).thenThrow(new SecurityException("Request access denied"));
        mvc.perform(get("/api/v1/external/data-pull/my-requests/10").param("username", "other@example.test"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.payload.requestId").value(10));
        mvc.perform(get("/api/v1/external/data-pull/my-requests/4"))
                .andExpect(status().isForbidden());
        verify(service).getMyRequest(10L);
        verify(service).getMyRequest(4L);
    }
}
