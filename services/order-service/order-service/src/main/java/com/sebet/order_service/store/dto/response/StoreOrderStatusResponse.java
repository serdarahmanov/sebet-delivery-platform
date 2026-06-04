package com.sebet.order_service.store.dto.response;

import com.sebet.order_service.shared.enums.OrderStatus;

/**
 * Lightweight status-only response for the store.
 *
 * Endpoint : GET /api/v1/store/orders/{orderId}/status
 * Source   : Cache 4
 * Returns  : StoreOrderStatusResponse
 *
 * Used by kitchen display screens or polling clients that only need
 * the current status without loading the full order payload.
 */
public record StoreOrderStatusResponse(
        String orderId,
        OrderStatus status
) {}
