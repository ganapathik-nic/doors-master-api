package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.dto.ReportExecutionRequest;
import org.gepnic.doors.masterapi.service.ReportViewerService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/master/execution")
@RequiredArgsConstructor
public class ExecutionController {
    private final ReportViewerService reports;

    @GetMapping("/run")
    public Flux<Object> executeReport(@RequestParam String agentId, @RequestParam String queryName,
            @RequestParam(required = false) String userId, Principal principal) {
        return run(agentId, queryName, Map.of(), principal);
    }

    @PostMapping("/execute")
    public Flux<Object> execute(@jakarta.validation.Valid @RequestBody ExecutionRequest request, Principal principal) {
        return run(request.agentId(), request.queryName(), request.params(), principal);
    }

    private Flux<Object> run(String agentId, String name, Map<String, Object> params, Principal principal) {
        return Flux.fromIterable(reports.executeReport(ReportExecutionRequest.builder()
                .agentId(agentId).queryUniqueName(name).params(params == null ? Map.of() : params)
                .performedBy(principal.getName()).build()).data()).cast(Object.class);
    }

    public record ExecutionRequest(@jakarta.validation.constraints.NotBlank String agentId,
            @jakarta.validation.constraints.NotBlank String queryName, Map<String, Object> params) {}
}
