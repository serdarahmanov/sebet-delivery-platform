package com.sebet.order_service.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * Validates the {@code X-Store-Id} header on every inbound request to
 * {@code /api/v1/store/**}.
 *
 * The header is expected to be injected by the API gateway after verifying
 * the store's session/token — this interceptor only enforces that it is
 * present and well-formed, not that the value is authentic.
 *
 * Mirrors the behaviour of {@link UserIdInterceptor} for the customer API.
 */
@Component
public class StoreIdInterceptor implements HandlerInterceptor {

    private static final int MAX_STORE_ID_LENGTH = 128;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String storeId = request.getHeader("X-Store-Id");
        if (storeId == null || storeId.isBlank() || storeId.length() > MAX_STORE_ID_LENGTH) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing or invalid X-Store-Id header");
            return false;
        }
        return true;
    }
}
