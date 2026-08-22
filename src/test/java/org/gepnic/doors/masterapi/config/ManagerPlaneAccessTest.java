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
}
