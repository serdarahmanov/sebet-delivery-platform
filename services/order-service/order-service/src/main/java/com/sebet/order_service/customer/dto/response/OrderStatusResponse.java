package com.sebet.order_service.customer.dto.response;

/**
 * Lightweight status-only response.
 *
 * Endpoint : GET /api/v1/orders/{orderId}/status
 * Source   : Cache 4
 * Returns  : OrderStatusResponse
 *
 * Used by polling clients (kitchen screen, push-notification triggers)
 * that only need the current status string and do not want to load
 * the full order payload.
 */
public record OrderStatusResponse(
        String orderId,
        /** Current status string, e.g. {@code "OUT_FOR_DELIVERY"}. */
        String status
) {}
