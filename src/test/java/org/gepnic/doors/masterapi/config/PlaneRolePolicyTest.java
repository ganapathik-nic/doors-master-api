package org.gepnic.doors.masterapi.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaneRolePolicyTest {

    private PlaneProperties properties;
    private PlaneRolePolicy policy;

    @BeforeEach
    void setUp() {
        properties = new PlaneProperties();
        properties.setEnforced(true);
        properties.setAdminHosts(List.of("doorsadmin.doors.test"));
        properties.setExternalHosts(List.of("doorsexternal.doors.test"));
        properties.setApiHosts(List.of("doorsapi.doors.test"));
        policy = new PlaneRolePolicy(properties);
    }

    @Test
    void adminPlaneAllowsOnlyPrivilegedInteractiveRoles() {
        assertTrue(policy.isRoleAllowed("doorsadmin.doors.test", "DataManager"));
        assertTrue(policy.isRoleAllowed("doorsadmin.doors.test", "SecurityAdmin"));
        assertTrue(policy.isRoleAllowed("doorsadmin.doors.test", "Developer"));
        assertTrue(policy.isRoleAllowed("doorsadmin.doors.test", "ROLE_ADMIN"));
        assertFalse(policy.isRoleAllowed("doorsadmin.doors.test", "External"));
        assertFalse(policy.isRoleAllowed("doorsadmin.doors.test", "DataViewer"));
        assertFalse(policy.isRoleAllowed("doorsadmin.doors.test", "ApiUser"));
    }

    @Test
    void externalPlaneAllowsOnlyStandardInteractiveRoles() {
        assertTrue(policy.isRoleAllowed("doorsexternal.doors.test", "External"));
        assertTrue(policy.isRoleAllowed("doorsexternal.doors.test", "DataViewer"));
        assertTrue(policy.isRoleAllowed("doorsexternal.doors.test", "ApiUser"));
        assertFalse(policy.isRoleAllowed("doorsexternal.doors.test", "Developer"));
        assertFalse(policy.isRoleAllowed("doorsexternal.doors.test", "DataManager"));
        assertFalse(policy.isRoleAllowed("doorsexternal.doors.test", "SecurityAdmin"));
    }

    @Test
    void apiAndUnknownPlanesRejectInteractiveUsers() {
        assertFalse(policy.isRoleAllowed("doorsapi.doors.test", "External"));
        assertFalse(policy.isRoleAllowed("doorsapi.doors.test", "ApiUser"));
        assertFalse(policy.isRoleAllowed("unassigned.doors.test", "DataManager"));
    }

    @Test
    void disabledEnforcementPreservesSingleHostDevelopmentMode() {
        properties.setEnforced(false);
        assertTrue(policy.isRoleAllowed("localhost", "External"));
    }
}
