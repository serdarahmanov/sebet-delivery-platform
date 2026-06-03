package com.sebet.order_service.customer.dto.response;

/**
 * Lightweight response carrying only the delivery verification code.
 *
 * Endpoint : GET /api/v1/orders/{orderId}/verification-code
 * Source   : Cache 7 (order:verification:{orderId})
 * Returns  : VerificationCodeResponse
 *
 * Used as an HTTP fallback for customers whose app was offline when
 * the WebSocket push was sent (status transition to ARRIVED).
 *
 * Returns 404 when the code has not yet been generated
 * (order has not reached ARRIVED status).
 * Returns 404 when Cache 7 has expired (30-minute window elapsed).
 */
public record VerificationCodeResponse(
        String orderId,
        /**
         * Zero-padded 2-digit code the customer reads out to the driver.
         * Always a 2-character string — e.g. {@code "04"}, {@code "47"}.
         */
        String code
) {}
