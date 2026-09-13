package org.gepnic.doors.masterapi.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.ForwardedHeaderFilter;
import java.util.*;

/** Inspect the real socket peer before Spring interprets forwarding headers. */
@Configuration
public class TrustedProxyConfiguration {
    public static final String CLIENT_IP = "DOORS_TRUSTED_CLIENT_IP";

    @Bean
    FilterRegistrationBean<Filter> proxyBoundary(
            @Value("${doors.security.trusted-proxies:127.0.0.1/32,::1/128}") List<String> proxies) {
        List<IpAddressMatcher> trusted = proxies.stream().map(String::trim).filter(s -> !s.isEmpty())
                .map(IpAddressMatcher::new).toList();
        FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<>((request, response, chain) -> {
            HttpServletRequest http = (HttpServletRequest) request;
            boolean trustedPeer = trusted.stream().anyMatch(m -> m.matches(http.getRemoteAddr()));
            String effective = http.getRemoteAddr();
            String forwarded = trustedPeer ? http.getHeader("X-Forwarded-For") : null;
            if (forwarded != null) {
                String[] hops = forwarded.split(",");
                for (int i = hops.length - 1; i >= 0; i--) {
                    String hop = hops[i].trim();
                    // Literal IP addresses only; never perform DNS resolution for a header.
                    if (!hop.matches("[0-9a-fA-F:.]+")) break;
                    effective = hop;
                    if (trusted.stream().noneMatch(m -> m.matches(hop))) break;
                }
            }
            String clientIp = effective;
            http.setAttribute(CLIENT_IP, clientIp);
            chain.doFilter(new HttpServletRequestWrapper(http) {
                private boolean forwardedName(String name) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    return lower.equals("forwarded") || lower.startsWith("x-forwarded-")
                            || lower.equals("x-real-ip") || lower.equals("x-doors-manager-plane");
                }
                @Override public String getHeader(String name) {
                    if (!trustedPeer && forwardedName(name)) return null;
                    // A normalized chain prevents a forged leftmost address being consumed downstream.
                    if (trustedPeer && name.equalsIgnoreCase("X-Forwarded-For")) return clientIp;
                    if (name.equalsIgnoreCase("Forwarded")) return null;
                    return super.getHeader(name);
                }
                @Override public Enumeration<String> getHeaders(String name) {
                    if (!forwardedName(name)) return super.getHeaders(name);
                    String value = getHeader(name);
                    return Collections.enumeration(value == null ? List.of() : List.of(value));
                }
                @Override public Enumeration<String> getHeaderNames() {
                    return Collections.enumeration(Collections.list(super.getHeaderNames()).stream()
                            .filter(n -> getHeader(n) != null).toList());
                }
            }, response);
        });
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    FilterRegistrationBean<ForwardedHeaderFilter> trustedForwardedHeaders() {
        var bean = new FilterRegistrationBean<>(new ForwardedHeaderFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return bean;
    }

    public static String clientIp(HttpServletRequest request) {
        Object resolved = request.getAttribute(CLIENT_IP);
        return resolved == null ? request.getRemoteAddr() : resolved.toString();
    }
}
