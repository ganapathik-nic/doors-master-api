package org.gepnic.doors.masterapi.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.gepnic.doors.masterapi.model.Agent;

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

    @Test
    void externalAgentListingIsRestrictedToAssignedNodes() {
        Agent dev01 = new Agent();
        dev01.setAgentId("Dev-01");
        Agent dev02 = new Agent();
        dev02.setAgentId("Dev-02");

        List<Agent> visible = AgentController.filterAssignedAgents(
                List.of(dev01, dev02), List.of(" dev-01 "));

        assertEquals(List.of("Dev-01"), visible.stream().map(Agent::getAgentId).toList());
    }

    @Test
    void apiUserUsesExternalSelfServiceAgentScope() {
        var apiUser = new UsernamePasswordAuthenticationToken(
                "api@example.test",
                null,
                List.of(new SimpleGrantedAuthority("ApiUser")));

        assertTrue(AgentController.isExternalSelfService(apiUser));
    }
}
