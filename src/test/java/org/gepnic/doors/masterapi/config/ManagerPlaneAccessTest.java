package org.gepnic.doors.masterapi.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagerPlaneAccessTest {

    @Test
    void requiresExactTrustedProxyTokenWhenEnforced() {
        ManagerPlaneAccess access = new ManagerPlaneAccess(true, "01234567890123456789012345678901");
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertFalse(access.isAllowed(request));
        request.addHeader("X-DOORS-MANAGER-PLANE", "wrong-token");
        assertFalse(access.isAllowed(request));

        MockHttpServletRequest trusted = new MockHttpServletRequest();
        trusted.addHeader("X-DOORS-MANAGER-PLANE", "01234567890123456789012345678901");
        assertTrue(access.isAllowed(trusted));
    }

    @Test
    void blankUserAllowlistAllowsEveryIp() {
        ManagerPlaneAccess access = new ManagerPlaneAccess(false, "");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        assertTrue(access.isVpnIpAllowed(request, null));
        assertTrue(access.isVpnIpAllowed(request, "  "));
    }

    @Test
    void populatedAllowlistIsEnforcedForAnyUserEvenWithoutManagerPlaneEnforcement() {
        ManagerPlaneAccess access = new ManagerPlaneAccess(false, "");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        assertTrue(access.isVpnIpAllowed(request, "192.0.2.4, 203.0.113.0/24"));
        assertFalse(access.isVpnIpAllowed(request, "192.0.2.4; 198.51.100.0/24"));
    }

    @Test
    void usesOriginalForwardedClientIp() {
        ManagerPlaneAccess access = new ManagerPlaneAccess(false, "");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.42, 127.0.0.1");
        request.setAttribute(TrustedProxyConfiguration.CLIENT_IP, "198.51.100.42");
        assertTrue(access.isVpnIpAllowed(request, "198.51.100.42"));
        assertFalse(access.isVpnIpAllowed(request, "127.0.0.1"));
    }
}
