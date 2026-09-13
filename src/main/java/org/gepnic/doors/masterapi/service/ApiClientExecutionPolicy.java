package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.repository.ClientQueryMapRepository;
import org.gepnic.doors.masterapi.repository.SqlTemplateRepository;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ApiClientExecutionPolicy {
    private final ClientQueryMapRepository mappings;
    private final SqlTemplateRepository templates;

    public void authorize(ApiClient client, String queryName) {
        if (!Boolean.TRUE.equals(client.getIsActive())) throw new SecurityException("Inactive API client");
        var template = templates.findByUniqueName(queryName)
                .filter(t -> Boolean.TRUE.equals(t.getIsActive()) && "APPROVED".equals(t.getStatus()))
                .orElseThrow(() -> new SecurityException("No active approved template"));
        var mapping = mappings.findByClientIdAndQueryId(client.getClientId(), template.getQueryId())
                .orElseThrow(() -> new SecurityException("Client is not authorized for this template"));
        String column = mapping.getResponseFilterColumn();
        String value = mapping.getResponseFilterValue();
        if ((column != null && !column.isBlank()) || (value != null && !value.isBlank())) {
            if (!ClientSpecificDataSegregationService.isSafeJsonField(column) || value == null || value.isBlank())
                throw new SecurityException("Invalid response segregation policy");
        }
    }
}
