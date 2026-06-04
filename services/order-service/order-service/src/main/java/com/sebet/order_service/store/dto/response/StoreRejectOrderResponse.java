package com.sebet.order_service.store.dto.response;

import com.sebet.order_service.shared.enums.OrderCancellationReason;
import com.sebet.order_service.shared.enums.OrderStatus;

/**
 * Confirmation returned after the store successfully rejects a PENDING order.
 *
 * Endpoint : POST /api/v1/store/orders/{orderId}/reject
 * Transition: PENDING → CANCELLED
 * Returns  : StoreRejectOrderResponse
 *
 * Returns 409 Conflict if the order is not in PENDING status.
 * Returns 403 Forbidden if the storeId on the order does not match X-Store-Id.
 */
public record StoreRejectOrderResponse(
        String orderId,
        /** Always {@link OrderStatus#CANCELLED} on a successful response. */
        OrderStatus status,
        OrderCancellationReason reason,
        /** ISO-8601 timestamp of the cancellation. */
        String cancelledAt
) {}
