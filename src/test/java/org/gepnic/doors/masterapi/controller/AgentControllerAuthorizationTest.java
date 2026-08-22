package org.gepnic.doors.masterapi.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentControllerAuthorizationTest {

    @Test
    void developerAgentListingAlwaysForcesSandboxFilter() {
        var developer = new UsernamePasswordAuthenticationToken(
                "developer@example.test",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_DEVELOPER")));

        assertTrue(AgentController.effectiveSandboxFilter(false, developer));
    }

    @Test
    void managerAgentListingKeepsRequestedFilter() {
        var manager = new UsernamePasswordAuthenticationToken(
                "manager@example.test",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_DATAMANAGER")));

        assertFalse(AgentController.effectiveSandboxFilter(false, manager));
    }
}
