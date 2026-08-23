package org.gepnic.doors.masterapi.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaneRoutingFilterTest {

    private PlaneRoutingFilter filter;

    @BeforeEach
    void setUp() {
        PlaneProperties properties = new PlaneProperties();
        properties.setAdminHosts(List.of("doorsadmin.doors.test"));
        properties.setExternalHosts(List.of("doorsexternal.doors.test"));
        properties.setApiHosts(List.of("doorsapi.doors.test"));
        filter = new PlaneRoutingFilter(properties);
    }

    @Test
    void resolvesConfiguredHostsToPlanes() {
        assertEquals(PlaneRoutingFilter.Plane.ADMIN, filter.resolvePlane("doorsadmin.doors.test"));
        assertEquals(PlaneRoutingFilter.Plane.EXTERNAL, filter.resolvePlane("doorsexternal.doors.test"));
        assertEquals(PlaneRoutingFilter.Plane.API, filter.resolvePlane("doorsapi.doors.test"));
        assertEquals(PlaneRoutingFilter.Plane.UNKNOWN, filter.resolvePlane("localhost"));
    }

    @Test
    void adminPlaneCannotExposeMachineExecutionEndpoint() {
        assertTrue(filter.isAllowed(PlaneRoutingFilter.Plane.ADMIN, "/api/v1/master/templates/list"));
        assertFalse(filter.isAllowed(PlaneRoutingFilter.Plane.ADMIN, "/api/v1/external/execute/report"));
        assertFalse(filter.isAllowed(PlaneRoutingFilter.Plane.ADMIN, "/api/v1/master/gateway/orchestrate/report"));
    }

    @Test
    void externalPlaneCannotExposeAdministration() {
        assertTrue(filter.isAllowed(PlaneRoutingFilter.Plane.EXTERNAL, "/api/v1/external/data-pull/submit"));
        assertFalse(filter.isAllowed(PlaneRoutingFilter.Plane.EXTERNAL, "/api/v1/admin/users/list"));
        assertFalse(filter.isAllowed(PlaneRoutingFilter.Plane.EXTERNAL, "/api/v1/external/execute/report"));
    }

    @Test
    void apiPlaneOnlyExposesMachineContractSurface() {
        assertTrue(filter.isAllowed(PlaneRoutingFilter.Plane.API, "/api/v1/master/gateway/orchestrate/report"));
        assertTrue(filter.isAllowed(PlaneRoutingFilter.Plane.API, "/v3/api-docs"));
        assertFalse(filter.isAllowed(PlaneRoutingFilter.Plane.API, "/api/v1/master/dashboard/stats"));
    }
}
