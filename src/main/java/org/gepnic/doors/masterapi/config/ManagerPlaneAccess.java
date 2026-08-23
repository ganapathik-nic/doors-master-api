package org.gepnic.doors.masterapi.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.net.InetAddress;

@Component
public class ManagerPlaneAccess {

    private final boolean enforced;
    private final String expectedToken;

    public ManagerPlaneAccess(
            @Value("${doors.security.manager-plane-enforced:false}") boolean enforced,
            @Value("${doors.security.manager-plane-token:}") String expectedToken) {
        this.enforced = enforced;
        this.expectedToken = expectedToken;
        if (enforced && expectedToken.length() < 24) {
            throw new IllegalStateException("Manager-plane token must contain at least 24 characters");
        }
    }

    public boolean isAllowed(HttpServletRequest request) {
        if (!enforced) return true;
        String supplied = request.getHeader("X-DOORS-MANAGER-PLANE");
        return supplied != null && MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    public boolean isVpnIpAllowed(HttpServletRequest request, String configuredIpOrCidr) {
        // IP allowlisting is an optional per-user control and is independent of
        // manager-plane token enforcement. An empty allowlist means unrestricted.
        if (configuredIpOrCidr == null || configuredIpOrCidr.isBlank()) return true;
        String forwarded = request.getHeader("X-Forwarded-For");
        String clientIp = forwarded == null || forwarded.isBlank()
                ? request.getRemoteAddr()
                : forwarded.split(",")[0].trim();
        return java.util.Arrays.stream(configuredIpOrCidr.split("[,;\\r\\n]+"))
                .map(String::trim)
                .filter(rule -> !rule.isEmpty())
                .anyMatch(rule -> matches(clientIp, rule));
    }

    private boolean matches(String clientIp, String configuredIpOrCidr) {
        try {
            String[] rule = configuredIpOrCidr.trim().split("/", 2);
            byte[] client = InetAddress.getByName(clientIp).getAddress();
            byte[] network = InetAddress.getByName(rule[0]).getAddress();
            if (client.length != network.length) return false;
            int prefix = rule.length == 2 ? Integer.parseInt(rule[1]) : client.length * 8;
            if (prefix < 0 || prefix > client.length * 8) return false;
            for (int i = 0; i < client.length; i++) {
                int remaining = prefix - (i * 8);
                int mask = remaining >= 8 ? 0xff : remaining <= 0 ? 0 : (0xff << (8 - remaining)) & 0xff;
                if ((client[i] & mask) != (network[i] & mask)) return false;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
