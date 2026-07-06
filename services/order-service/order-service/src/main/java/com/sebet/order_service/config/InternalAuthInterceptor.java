package com.sebet.order_service.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * Guards all {@code /api/v1/internal/**} endpoints against unauthorized callers.
 *
 * Callers must supply an {@code X-Internal-Key} header whose value matches the
 * secret configured under {@code order-service.internal.secret}.
 *
 * Behaviour by configuration:
 *   - Secret configured : header must be present AND match the configured value.
 *   - Secret not set    : header must be present and non-blank (dev/test convenience).
 *
 * The secret is expected to be injected by the API gateway or set directly in the
 * calling service's outbound headers — this interceptor does not issue or rotate secrets.
 */
@Component
public class InternalAuthInterceptor implements HandlerInterceptor {

    @Value("${order-service.internal.secret:}")
    private String configuredSecret;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String key = request.getHeader("X-Internal-Key");
        if (key == null || key.isBlank()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing X-Internal-Key header");
            return false;
        }
        if (!configuredSecret.isBlank() && !configuredSecret.equals(key)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid X-Internal-Key");
            return false;
        }
        return true;
    }
}
