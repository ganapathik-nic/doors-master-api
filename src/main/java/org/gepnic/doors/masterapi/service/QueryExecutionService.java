package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.dto.ReportExecutionRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.Map;

/** Legacy adapter sharing the governed report execution path and current Agent protocol. */
@Service
@RequiredArgsConstructor
public class QueryExecutionService {
    private final ReportViewerService reports;

    public List<Map<String, Object>> fetchFromAgent(String agentId, String queryName) {
        return executeRemote(agentId, queryName, null, Map.of())
                .map(value -> (Map<String, Object>) value).collectList().block();
    }

    public Flux<Object> executeRemote(String agentId, String queryName, String ignoredUserId,
                                      Map<String, Object> params) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            throw new SecurityException("Authenticated execution context is required");
        }
        return Flux.fromIterable(reports.executeReport(ReportExecutionRequest.builder()
                .agentId(agentId).queryUniqueName(queryName).performedBy(auth.getName())
                .params(params == null ? Map.of() : params).build()).data()).cast(Object.class);
    }
}
