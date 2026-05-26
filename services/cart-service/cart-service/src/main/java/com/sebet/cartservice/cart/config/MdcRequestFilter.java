package com.sebet.cartservice.cart.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@Component
@Order(1)
public class MdcRequestFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        String userId = request.getHeader("X-User-Id");

        MDC.put("requestId", requestId);
        MDC.put("method", request.getMethod());
        MDC.put("path", request.getRequestURI());
        if (userId != null && !userId.isBlank()) {
            MDC.put("userId", userId);
        }

        response.setHeader("X-Request-Id", requestId);

        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            int status = response.getStatus();
            long elapsedMs = System.currentTimeMillis() - start;
            String method = request.getMethod();
            String path   = request.getRequestURI();

            if (status >= 500) {
                log.error("request_completed method={} path={} status={} elapsed_ms={}",
                        method, path, status, elapsedMs);
            } else if (status >= 400) {
                log.warn("request_completed method={} path={} status={} elapsed_ms={}",
                        method, path, status, elapsedMs);
            } else {
                log.info("request_completed method={} path={} status={} elapsed_ms={}",
                        method, path, status, elapsedMs);
            }
            MDC.clear();
        }
    }
}
