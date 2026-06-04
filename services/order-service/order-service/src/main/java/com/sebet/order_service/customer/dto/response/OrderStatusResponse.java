package com.sebet.order_service.customer.dto.response;

import com.sebet.order_service.shared.enums.OrderStatus;

/**
 * Lightweight status-only response.
 *
 * Endpoint : GET /api/v1/orders/{orderId}/status
 * Source   : Cache 4
 * Returns  : OrderStatusResponse
 *
 * Used by polling clients (kitchen screens, push-notification triggers)
 * that only need the current status and do not want to load the full order payload.
 */
public record OrderStatusResponse(
        String orderId,
        OrderStatus status
) {}
