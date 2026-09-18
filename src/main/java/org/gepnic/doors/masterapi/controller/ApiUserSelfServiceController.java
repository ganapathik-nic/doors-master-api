package org.gepnic.doors.masterapi.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.entity.ClientQueryMap;
import org.gepnic.doors.masterapi.model.SqlTemplate;
import org.gepnic.doors.masterapi.model.User;
import org.gepnic.doors.masterapi.repository.ClientQueryMapRepository;
import org.gepnic.doors.masterapi.repository.SqlTemplateRepository;
import org.gepnic.doors.masterapi.repository.UserRepository;
import org.gepnic.doors.masterapi.service.SwaggerSessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/external/api-user")
@RequiredArgsConstructor
public class ApiUserSelfServiceController {
    @org.springframework.beans.factory.annotation.Autowired
    private org.gepnic.doors.masterapi.service.GatewayProtocolService protocolService;

    @GetMapping("/clients/{clientId}/protocol-policies")
    public ResponseEntity<?> protocolPolicies(@PathVariable Long clientId, Authentication authentication) {
        User user=apiUser(authentication);
        if(user==null || user.getApiClients().stream().noneMatch(c -> clientId.equals(c.getClientId()) && Boolean.TRUE.equals(c.getIsActive())))
            return forbidden("Client is not assigned to this account");
        return ResponseEntity.ok(ApiResponse.success(protocolService.policies(clientId),"Protocol policies"));
    }
    private final UserRepository userRepository;
    private final ClientQueryMapRepository mappingRepository;
    private final SqlTemplateRepository templateRepository;
    private final JdbcOperations jdbcTemplate;
    private final SwaggerSessionService swaggerSessionService;

    @GetMapping("/clients")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAssignedClients(Authentication authentication) {
        User user = apiUser(authentication);
        if (user == null) return forbidden("This account is not an API user");
        List<Map<String, Object>> profiles = user.getApiClients().stream()
                .filter(client -> Boolean.TRUE.equals(client.getIsActive()))
                .sorted(Comparator.comparing(ApiClient::getClientName, String.CASE_INSENSITIVE_ORDER))
                .map(this::profile).toList();
        if (profiles.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error("No active API clients are assigned to this account", 409));
        }
        return ResponseEntity.ok(ApiResponse.success(profiles, "Assigned API clients retrieved"));
    }

    @PostMapping("/swagger-sessions")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createSwaggerSession(
            @RequestBody Map<String, Object> body, Authentication authentication, HttpServletRequest request) {
        try {
            User user = apiUser(authentication);
            if (user == null) return forbidden("This account is not an API user");
            Long clientId = Long.valueOf(String.valueOf(body.get("clientId")));
            if (user.getApiClients().stream().noneMatch(client -> clientId.equals(client.getClientId()))) {
                return forbidden("The selected API client is not assigned to this account");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> requestBody = (Map<String, Object>) body.get("body");
            Map<String, Object> launch = swaggerSessionService.createLaunch(
                    clientId, String.valueOf(body.get("uniqueName")), requestBody, null,
                    authentication.getName(), request.getHeader("User-Agent"));
            return ResponseEntity.ok(ApiResponse.success(launch, "Single-use Swagger launch created"));
        } catch (SecurityException exception) {
            return forbidden(exception.getMessage());
        } catch (NoSuchElementException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(exception.getMessage(), 404));
        } catch (RuntimeException exception) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid Swagger launch request", 400));
        }
    }

    private User apiUser(Authentication authentication) {
        if (authentication == null) return null;
        return userRepository.findByUsername(authentication.getName())
                .filter(user -> "ApiUser".equalsIgnoreCase(user.getRole())).orElse(null);
    }

    private Map<String, Object> profile(ApiClient client) {
        List<Map<String, Object>> details = new ArrayList<>();
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
            item.put("responseFilterValue", mapping.getResponseFilterValue());
            details.add(item);
        }
        List<String> agents = jdbcTemplate.queryForList(
                "SELECT agent_id FROM user_authorized_agents WHERE user_name = ?", String.class, client.getClientName());
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("clientId", client.getClientId());
        profile.put("clientName", client.getClientName());
        profile.put("description", client.getDescription());
        profile.put("apiKey", client.getApiKey());
        profile.put("ipWhitelist", client.getIpWhitelist());
        profile.put("isEncryptionEnabled", client.isEncryptionEnabled());
        profile.put("isActive", client.getIsActive());
        profile.put("assignedAgents", agents);
        profile.put("authorizedDetails", details);
        return profile;
    }

    private <T> ResponseEntity<ApiResponse<T>> forbidden(String message) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(message, 403));
    }
}
