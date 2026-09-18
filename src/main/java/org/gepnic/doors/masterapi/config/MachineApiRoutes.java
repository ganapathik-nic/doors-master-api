package org.gepnic.doors.masterapi.config;

/** Machine-consumer routes only. Portal reporting, dashboards and public key discovery are separate. */
public final class MachineApiRoutes {
    private MachineApiRoutes() {}
    public static final String[] PATTERNS = {
            "/api/v1/master/gateway/handshake", "/api/v1/master/gateway/orchestrate/**",
            "/api/v1/master/gateway/documents/**", "/api/v1/master/gateway/telemetry/**",
            "/api/v1/master/reports/orchestrate/**", "/api/v1/external/execute/**"
    };
    public static boolean matches(String path) {
        for (String pattern : PATTERNS) {
            if (pattern.endsWith("/**")) {
                String root = pattern.substring(0, pattern.length()-3);
                if (path.equals(root) || path.startsWith(root + "/")) return true;
            } else if (path.equals(pattern)) return true;
        }
        return false;
    }
}
