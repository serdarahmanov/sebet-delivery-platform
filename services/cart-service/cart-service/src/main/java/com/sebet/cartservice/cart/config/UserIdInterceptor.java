package com.sebet.cartservice.cart.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

@Component
public class UserIdInterceptor implements HandlerInterceptor {

    private static final int MAX_USER_ID_LENGTH = 128;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String userId = request.getHeader("X-User-Id");
        if (userId == null || userId.isBlank() || userId.length() > MAX_USER_ID_LENGTH) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing or invalid X-User-Id header");
            return false;
        }
        return true;
    }
}
