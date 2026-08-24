package org.gepnic.doors.masterapi.controller;

import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.model.User;
import org.gepnic.doors.masterapi.repository.ClientQueryMapRepository;
import org.gepnic.doors.masterapi.repository.SqlTemplateRepository;
import org.gepnic.doors.masterapi.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiUserSelfServiceControllerTest {

    private final UserRepository users = mock(UserRepository.class);
    private final ClientQueryMapRepository mappings = mock(ClientQueryMapRepository.class);
    private final SqlTemplateRepository templates = mock(SqlTemplateRepository.class);
    private final ApiUserSelfServiceController controller =
            new ApiUserSelfServiceController(users, mappings, templates);

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

        var response = controller.getAssignedClient(authentication);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(42L, response.getBody().getPayload().get("clientId"));
    }

    @Test
    void rejectsApiUserWithoutMapping() {
        Authentication authentication = authentication("unmapped@example.test");
        when(users.findByUsername(authentication.getName())).thenReturn(Optional.of(apiUser(null)));

        assertEquals(409, controller.getAssignedClient(authentication).getStatusCode().value());
    }

    @Test
    void rejectsInactiveMappedClient() {
        ApiClient client = new ApiClient();
        client.setIsActive(false);
        Authentication authentication = authentication("inactive@example.test");
        when(users.findByUsername(authentication.getName())).thenReturn(Optional.of(apiUser(client)));

        assertEquals(403, controller.getAssignedClient(authentication).getStatusCode().value());
    }

    private User apiUser(ApiClient client) {
        User user = new User();
        user.setRole("ApiUser");
        user.setApiClient(client);
        return user;
    }

    private Authentication authentication(String username) {
        return new UsernamePasswordAuthenticationToken(username, "unused", List.of());
    }
}
