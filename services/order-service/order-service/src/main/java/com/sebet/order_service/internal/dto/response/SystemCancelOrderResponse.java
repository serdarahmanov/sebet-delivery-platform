package com.sebet.order_service.internal.dto.response;

/**
 * Confirmation returned after a system-initiated cancellation.
 *
 * Endpoints : POST /api/v1/internal/orders/{orderId}/system-cancel
 *             POST /api/v1/internal/orders/{orderId}/admin-cancel
 */
public record SystemCancelOrderResponse(
        String orderId,
        /** Always {@code "CANCELLED"} on success. */
        String status,
        String reason,
        /** ISO-8601 timestamp of when the cancellation was applied. */
        String cancelledAt
) {}
