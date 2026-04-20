package org.gepnic.doors.masterapi.config;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties properties;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // 🛡️ FIX 1: Ignore CORS Pre-flight requests
        // Without this, 1 click = 2 tokens used. 10 clicks would empty a 20-token bucket.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String uri = request.getRequestURI();
        String key = resolveClientKey(request); // Usually IP or Username
        
        // Determine which policy to use based on path
        RateLimitProperties.Policy policy = uri.contains(properties.getGatewayPathPattern()) 
                ? properties.getRateLimit().getGateway() 
                : properties.getRateLimit().getUi();

        Bucket bucket = buckets.computeIfAbsent(key + ":" + uri, k -> createNewBucket(policy));

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            log.warn("🛡️ DOORS-LIMIT: Rate limit exceeded for key: {} on URI: {}", key, uri);
            response.setStatus(429);
            response.setHeader("X-Rate-Limit-Retry-After-Seconds", "60");
            response.getWriter().write("Too many requests. Please wait a moment.");
        }
    }

    private Bucket createNewBucket(RateLimitProperties.Policy policy) {
        // Ensure we are using the values from the YAML
        return Bucket.builder()
                .addLimit(Bandwidth.classic(policy.getCapacity(), 
                          Refill.greedy(policy.getCapacity(), Duration.ofMinutes(policy.getRefillMinutes()))))
                .build();
    }

    private String resolveClientKey(HttpServletRequest request) {
        // Use Username if authenticated, else IP
        String authUser = request.getRemoteUser();
        return (authUser != null) ? authUser : request.getRemoteAddr();
    }
}