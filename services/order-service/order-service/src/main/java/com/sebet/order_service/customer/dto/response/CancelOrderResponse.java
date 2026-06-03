package com.sebet.order_service.customer.dto.response;

/**
 * Confirmation returned after a successful cancellation request.
 *
 * Endpoint : POST /api/v1/orders/{orderId}/cancel
 * Clears   : Cache 1, Cache 2, Cache 3, Cache 4
 * Returns  : CancelOrderResponse
 *
 * Only succeeds when the order is in PENDING or CONFIRMED status.
 * Any other status results in a 409 Conflict.
 */
public record CancelOrderResponse(
        String orderId,
        /** Always {@code "CANCELLED"} on a successful response. */
        String status,
        /** ISO-8601 timestamp of the cancellation. */
        String cancelledAt
) {}
