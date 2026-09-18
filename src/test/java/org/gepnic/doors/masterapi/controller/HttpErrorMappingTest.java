package org.gepnic.doors.masterapi.controller;

import org.gepnic.doors.masterapi.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HttpErrorMappingTest {
    @RestController static class Fixture {
        @GetMapping("/restricted") String restricted() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "private internal reason");
        }
        @GetMapping("/export/{id}") String export(@PathVariable("id") UUID id) { return id.toString(); }
    }
    @Test void intentionalNotFoundIsNotConvertedTo500() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new Fixture()).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/restricted")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOORS-RESOURCE-NOT-FOUND"))
                .andExpect(jsonPath("$.detail").value("Resource not found"));
    }
    @Test void malformedUuidReturns400WithoutReflectingInput() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new Fixture()).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/export/not-a-uuid")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOORS-REQUEST-INVALID"))
                .andExpect(jsonPath("$.detail").value("Request fields are missing or invalid"));
    }
}
