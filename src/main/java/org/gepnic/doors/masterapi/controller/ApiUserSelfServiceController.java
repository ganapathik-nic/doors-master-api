package org.gepnic.doors.masterapi.controller;

import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.entity.ClientQueryMap;
import org.gepnic.doors.masterapi.model.SqlTemplate;
import org.gepnic.doors.masterapi.model.User;
import org.gepnic.doors.masterapi.repository.ClientQueryMapRepository;
import org.gepnic.doors.masterapi.repository.SqlTemplateRepository;
import org.gepnic.doors.masterapi.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/external/api-user")
@RequiredArgsConstructor
public class ApiUserSelfServiceController {

    private final UserRepository userRepository;
    private final ClientQueryMapRepository mappingRepository;
    private final SqlTemplateRepository templateRepository;

    @GetMapping("/client")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAssignedClient(Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName()).orElse(null);
        if (user == null || !"ApiUser".equalsIgnoreCase(user.getRole())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("This account is not an API user", 403));
        }

        ApiClient client = user.getApiClient();
        if (client == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error("No API client is assigned to this account", 409));
        }
        if (!Boolean.TRUE.equals(client.getIsActive())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("The assigned API client is inactive", 403));
        }

        List<Map<String, Object>> templates = new ArrayList<>();
        for (ClientQueryMap mapping : mappingRepository.findByClientId(client.getClientId())) {
            SqlTemplate template = templateRepository.findById(mapping.getQueryId()).orElse(null);
            if (template == null || !Boolean.TRUE.equals(template.getIsActive())
                    || !"APPROVED".equalsIgnoreCase(template.getStatus())) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("queryId", template.getQueryId());
            item.put("uniqueName", template.getUniqueName());
            item.put("description", template.getDescription());
            item.put("parameters", template.getParameters());
            item.put("responseFilterColumn", mapping.getResponseFilterColumn());
            templates.add(item);
        }

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("clientId", client.getClientId());
        profile.put("clientName", client.getClientName());
        profile.put("description", client.getDescription());
        profile.put("apiKey", client.getApiKey());
        profile.put("ipWhitelist", client.getIpWhitelist());
        profile.put("encryptionEnabled", client.isEncryptionEnabled());
        profile.put("assignedAgents", user.getAssignedAgents());
        profile.put("authorizedTemplates", templates);
        return ResponseEntity.ok(ApiResponse.success(profile, "Assigned API client retrieved"));
    }
}
