package com.sebet.order_service.store.dto.response;

import com.sebet.order_service.shared.enums.OrderStatus;

/**
 * Confirmation returned after the store successfully accepts a PENDING order.
 *
 * Endpoint : POST /api/v1/store/orders/{orderId}/accept
 * Transition: PENDING → CONFIRMED
 * Returns  : StoreAcceptOrderResponse
 *
 * Returns 409 Conflict if the order is not in PENDING status.
 * Returns 403 Forbidden if the storeId on the order does not match X-Store-Id.
 */
public record StoreAcceptOrderResponse(
        String orderId,
        /** Always {@link OrderStatus#CONFIRMED} on a successful response. */
        OrderStatus status,
        /** ISO-8601 timestamp of when the order was accepted. */
        String confirmedAt
) {}
