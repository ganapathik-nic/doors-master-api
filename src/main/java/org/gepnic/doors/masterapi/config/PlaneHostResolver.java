package org.gepnic.doors.masterapi.config;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;

public final class PlaneHostResolver {

    private PlaneHostResolver() {}

    public static String resolve(HttpServletRequest request) {
        // Only trust proxy-supplied host data when the request reached Spring
        // through the same-machine Vite/nginx reverse proxy.
        if (isLoopback(request.getRemoteAddr())) {
            String forwardedHost = first(request.getHeader("X-Forwarded-Host"));
            if (forwardedHost != null && !forwardedHost.isBlank()) {
                return stripPort(forwardedHost);
            }
        }
        return request.getServerName();
    }

    private static String first(String value) {
        return value == null ? null : value.split(",", 2)[0].trim();
    }

    private static String stripPort(String host) {
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            return end > 0 ? host.substring(1, end) : host;
        }
        int colon = host.lastIndexOf(':');
        return colon > 0 && host.indexOf(':') == colon ? host.substring(0, colon) : host;
    }

    private static boolean isLoopback(String address) {
        try {
            return address != null && InetAddress.getByName(address).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }
}
