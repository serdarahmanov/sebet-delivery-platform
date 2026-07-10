package com.sebet.order_service.internal.dto.response;

/**
 * Confirmation returned after an active proposal is cancelled without
 * cancelling the order.
 *
 * Endpoint: POST /api/v1/internal/orders/{orderId}/cancel-active-proposal
 */
public record CancelActiveProposalResponse(
        String orderId,
        String status,
        String cancelledAt
) {}
