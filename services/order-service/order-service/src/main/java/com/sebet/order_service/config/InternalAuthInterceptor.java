package com.sebet.order_service.config;

import jakarta.annotation.PostConstruct;
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
 *   - Secret not set    : startup fails in every environment.
 *
 * The secret is expected to be injected by the API gateway or set directly in the
 * calling service's outbound headers — this interceptor does not issue or rotate secrets.
 */
@Component
public class InternalAuthInterceptor implements HandlerInterceptor {

    private final String configuredSecret;

    public InternalAuthInterceptor(
            @Value("${order-service.internal.secret:}") String configuredSecret
    ) {
        this.configuredSecret = configuredSecret == null ? "" : configuredSecret;
    }

    @PostConstruct
    void validateConfiguredSecret() {
        if (configuredSecret.isBlank()) {
            throw new IllegalStateException(
                    "order-service.internal.secret must be configured"
            );
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String key = request.getHeader("X-Internal-Key");
        if (key == null || key.isBlank()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing X-Internal-Key header");
            return false;
        }
        if (!configuredSecret.equals(key)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid X-Internal-Key");
            return false;
        }
        return true;
    }
}
