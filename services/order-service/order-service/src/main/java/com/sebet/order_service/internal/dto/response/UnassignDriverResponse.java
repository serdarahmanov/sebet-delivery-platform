package com.sebet.order_service.internal.dto.response;

/**
 * Confirmation returned after a driver is successfully unassigned from an order.
 *
 * Endpoint : POST /api/v1/internal/orders/{orderId}/unassign-driver
 */
public record UnassignDriverResponse(
        String orderId,
        /** The driver that was removed. Carried for audit and downstream cleanup. */
        String previousDriverId,
        /** Current order status — unchanged by the unassignment. */
        String status,
        /** Reason provided by the caller — echoed back for audit. */
        String reason
) {}
