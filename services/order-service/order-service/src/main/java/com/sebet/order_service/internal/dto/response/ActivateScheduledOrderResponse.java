package com.sebet.order_service.internal.dto.response;

/**
 * Confirmation returned after a scheduled order is activated into the live queue.
 *
 * Endpoint  : POST /api/v1/internal/orders/{orderId}/activate-scheduled
 * Transition: SCHEDULED → PENDING
 */
public record ActivateScheduledOrderResponse(
        String orderId,
        /** Always {@code "PENDING"} on success. */
        String status
) {}
