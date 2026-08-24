package org.gepnic.doors.masterapi.controller;

import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.model.User;
import org.gepnic.doors.masterapi.repository.ClientQueryMapRepository;
import org.gepnic.doors.masterapi.repository.SqlTemplateRepository;
import org.gepnic.doors.masterapi.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.Map;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiUserSelfServiceControllerTest {

    private final UserRepository users = mock(UserRepository.class);
    private final ClientQueryMapRepository mappings = mock(ClientQueryMapRepository.class);
    private final SqlTemplateRepository templates = mock(SqlTemplateRepository.class);
    private final JdbcOperations jdbc = mock(JdbcOperations.class);
    private final ApiUserSelfServiceController controller =
            new ApiUserSelfServiceController(users, mappings, templates, jdbc, null);

    @Test
    void derivesClientFromAuthenticatedUserWithoutAcceptingAClientId() {
        ApiClient client = new ApiClient();
        client.setClientId(42L);
        client.setClientName("NIC Client");
        client.setApiKey("secret-key");
        client.setIsActive(true);
        User user = apiUser(client);
        Authentication authentication = authentication("api.owner@example.test");
        when(users.findByUsername(authentication.getName())).thenReturn(Optional.of(user));
        when(mappings.findByClientId(42L)).thenReturn(List.of());
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(String.class), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of("Dev-01"));

        var response = controller.getAssignedClients(authentication);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(42L, response.getBody().getPayload().get(0).get("clientId"));
    }

    @Test
    void rejectsApiUserWithoutMapping() {
        Authentication authentication = authentication("unmapped@example.test");
        when(users.findByUsername(authentication.getName())).thenReturn(Optional.of(apiUser(null)));

        assertEquals(409, controller.getAssignedClients(authentication).getStatusCode().value());
    }

    @Test
    void rejectsInactiveMappedClient() {
        ApiClient client = new ApiClient();
        client.setIsActive(false);
        Authentication authentication = authentication("inactive@example.test");
        when(users.findByUsername(authentication.getName())).thenReturn(Optional.of(apiUser(client)));

        assertEquals(409, controller.getAssignedClients(authentication).getStatusCode().value());
    }

    @Test
    void returnsEveryActiveClientAssignedToTheUser() {
        ApiClient first = activeClient(42L, "Financial Evaluation");
        ApiClient second = activeClient(43L, "Tender Status");
        User user = apiUser(first);
        user.setApiClients(Set.of(first, second));
        Authentication authentication = authentication("multi@example.test");
        when(users.findByUsername(authentication.getName())).thenReturn(Optional.of(user));
        when(mappings.findByClientId(org.mockito.ArgumentMatchers.anyLong())).thenReturn(List.of());
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(String.class), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of("Dev-01"));

        assertEquals(2, controller.getAssignedClients(authentication).getBody().getPayload().size());
    }

    @Test
    void refusesSwaggerLaunchForAClientNotAssignedToTheUser() {
        User user = apiUser(activeClient(42L, "Financial Evaluation"));
        Authentication authentication = authentication("scoped@example.test");
        when(users.findByUsername(authentication.getName())).thenReturn(Optional.of(user));

        var response = controller.createSwaggerSession(
                Map.of("clientId", 99L, "uniqueName", "TenderStatus", "body", Map.of()),
                authentication, new MockHttpServletRequest());

        assertEquals(403, response.getStatusCode().value());
    }

    private User apiUser(ApiClient client) {
        User user = new User();
        user.setRole("ApiUser");
        user.setApiClients(client == null ? Set.of() : Set.of(client));
        return user;
    }

    private ApiClient activeClient(Long id, String name) {
        ApiClient client = new ApiClient();
        client.setClientId(id);
        client.setClientName(name);
        client.setApiKey("key-" + id);
        client.setIsActive(true);
        return client;
    }

    private Authentication authentication(String username) {
        return new UsernamePasswordAuthenticationToken(username, "unused", List.of());
    }
}
