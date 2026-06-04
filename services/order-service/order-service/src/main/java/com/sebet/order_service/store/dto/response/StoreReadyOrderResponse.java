package com.sebet.order_service.store.dto.response;

import com.sebet.order_service.shared.enums.OrderStatus;

/**
 * Confirmation returned after the store marks an order as packed and ready for pickup.
 *
 * Endpoint : POST /api/v1/store/orders/{orderId}/ready
 * Transition: CONFIRMED → READY_FOR_PICKUP
 * Returns  : StoreReadyOrderResponse
 *
 * Returns 409 Conflict if the order is not in CONFIRMED status.
 * Returns 403 Forbidden if the storeId on the order does not match X-Store-Id.
 */
public record StoreReadyOrderResponse(
        String orderId,
        /** Always {@link OrderStatus#READY_FOR_PICKUP} on a successful response. */
        OrderStatus status,
        /** ISO-8601 timestamp of when the order was marked ready. */
        String readyAt
) {}
