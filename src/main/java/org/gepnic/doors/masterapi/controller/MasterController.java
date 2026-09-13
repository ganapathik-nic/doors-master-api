package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.repository.AgentRepository;
import org.gepnic.doors.masterapi.repository.SqlTemplateRepository;
import org.gepnic.doors.masterapi.service.ReportViewerService;
import org.gepnic.doors.masterapi.dto.ReportExecutionRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/master")
@RequiredArgsConstructor
public class MasterController {
    private final AgentRepository agents;
    private final SqlTemplateRepository templates;
    private final ReportViewerService reports;

    /** Compatibility adapter: caller URL/SQL may only identify an approved registered pair. */
    @PostMapping("/stream")
    public Flux<Object> handleStream(@RequestParam String url, @RequestParam String sql, Principal principal) {
        var agent = agents.findByIsActiveTrue().stream()
                .filter(a -> url.equals(a.getBaseUrl())).findFirst()
                .orElseThrow(() -> new SecurityException("Unregistered execution destination"));
        var template = templates.findByStatusAndIsActive("APPROVED", true).stream()
                .filter(t -> sql.equals(t.getSqlText()) && t.getAuthorizedAgents().contains(agent.getAgentId()))
                .findFirst().orElseThrow(() -> new SecurityException("No approved registered query"));
        var result = reports.executeReport(ReportExecutionRequest.builder()
                .queryId(template.getQueryId()).agentId(agent.getAgentId())
                .performedBy(principal.getName()).params(Map.of()).build());
        return Flux.fromIterable(result.data()).cast(Object.class);
    }
}
