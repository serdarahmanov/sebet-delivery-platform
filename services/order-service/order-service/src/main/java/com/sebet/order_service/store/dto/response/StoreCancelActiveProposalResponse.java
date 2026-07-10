package com.sebet.order_service.store.dto.response;

/**
 * Confirmation returned after the store cancels its active proposal without
 * cancelling the order.
 *
 * Endpoint: POST /api/v1/store/orders/{orderId}/cancel-active-proposal
 */
public record StoreCancelActiveProposalResponse(
        String orderId,
        String status,
        String cancelledAt
) {}
