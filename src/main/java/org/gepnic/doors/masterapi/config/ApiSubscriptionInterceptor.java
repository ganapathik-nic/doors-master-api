package org.gepnic.doors.masterapi.config;

import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.repository.ApiClientRepository;
import org.gepnic.doors.masterapi.service.ApiSubscriptionService;
import org.gepnic.doors.masterapi.exception.DoorsApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ApiSubscriptionInterceptor implements HandlerInterceptor {
    public static final String CLIENT_ATTRIBUTE = "DOORS_EGRESS_CLIENT_ID";
    private final ApiClientRepository clients;
    private final ApiSubscriptionService subscriptions;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String key = request.getHeader("X-API-KEY");
        var client = (key == null || key.isBlank()) ? null : clients.findByApiKey(key).orElse(null);
        if (client == null) throw new DoorsApiException(HttpStatus.UNAUTHORIZED, "DOORS-AUTH-INVALID-API-KEY",
                "invalid-api-key", "Invalid API key", "A valid API client key is required.", false, Map.of());
        request.setAttribute(CLIENT_ATTRIBUTE, client.getClientId());
        // Re-evaluate every request, including handshakes, cached sessions, receipts and Swagger execution.
        subscriptions.enforce(client.getClientId());
        return true;
    }
}
