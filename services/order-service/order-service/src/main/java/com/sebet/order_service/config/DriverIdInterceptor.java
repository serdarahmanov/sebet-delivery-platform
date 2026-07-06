package com.sebet.order_service.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * Validates the {@code X-Driver-Id} header on every inbound request to
 * {@code /api/v1/driver/**}.
 *
 * The header is expected to be injected by the API gateway after verifying
 * the driver's session/token — this interceptor only enforces that it is
 * present and well-formed, not that the value is authentic.
 *
 * Mirrors the behaviour of {@link UserIdInterceptor} and {@link StoreIdInterceptor}.
 */
@Component
public class DriverIdInterceptor implements HandlerInterceptor {

    private static final int MAX_DRIVER_ID_LENGTH = 128;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String driverId = request.getHeader("X-Driver-Id");
        if (driverId == null || driverId.isBlank() || driverId.length() > MAX_DRIVER_ID_LENGTH) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing or invalid X-Driver-Id header");
            return false;
        }
        return true;
    }
}
