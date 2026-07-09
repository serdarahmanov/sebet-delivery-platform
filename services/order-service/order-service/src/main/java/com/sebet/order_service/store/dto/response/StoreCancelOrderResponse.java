package com.sebet.order_service.store.dto.response;

import com.sebet.order_service.shared.enums.OrderCancellationReason;
import com.sebet.order_service.shared.enums.OrderStatus;

/**
 * Confirmation returned after the store successfully cancels an accepted order.
 *
 * Endpoint : POST /api/v1/store/orders/{orderId}/cancel
 * Transition: CONFIRMED | AWAITING_CUSTOMER_RESPONSE → CANCELLED
 * Returns  : StoreCancelOrderResponse
 *
 * Requires Idempotency-Key.
 * Returns 409 Conflict  if the order is not in CONFIRMED or AWAITING_CUSTOMER_RESPONSE status.
 * Returns 409 Conflict  if the Idempotency-Key is reused with a different request body.
 * Returns 404 Not Found if the order does not belong to this store.
 */
public record StoreCancelOrderResponse(
        String orderId,
        /** Always {@link OrderStatus#CANCELLED} on a successful response. */
        OrderStatus status,
        /** The persisted cancellation reason. */
        OrderCancellationReason reason,
        /** ISO-8601 timestamp of the cancellation. */
        String cancelledAt
) {}
