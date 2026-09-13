package org.gepnic.doors.masterapi.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.repository.SqlTemplateRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/external")
@RequiredArgsConstructor
public class ExternalConsumerController {
    private final SqlTemplateRepository templates;
    private final ExternalGatewayController gateway;

    /** Legacy alias with the same policy and encrypted response contract as the gateway. */
    @PostMapping("/execute/{queryId}")
    public ResponseEntity<?> executeAuthorizedQuery(@PathVariable Long queryId,
            @RequestHeader("X-API-KEY") String apiKey,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) throws Exception {
        var template = templates.findById(queryId)
                .orElseThrow(() -> new SecurityException("Unknown template"));
        return gateway.proxyOrchestration(template.getUniqueName(), apiKey,
                body == null ? Map.of() : body, request);
    }
}
