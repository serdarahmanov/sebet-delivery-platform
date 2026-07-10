package com.sebet.order_service.internal.dto.response;

/**
 * Confirmation returned after an active proposal is cancelled and the order is
 * cancelled as well.
 *
 * Endpoint  : POST /api/v1/internal/orders/{orderId}/cancel-proposal-and-order
 * Transition: AWAITING_CUSTOMER_RESPONSE -> CANCELLED
 */
public record CancelProposalResponse(
        String orderId,
        /** Always {@code "CANCELLED"} on success. */
        String status,
        /** ISO-8601 timestamp of when the proposal and order were cancelled. */
        String cancelledAt
) {}
