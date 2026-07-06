package com.sebet.order_service.internal.dto.response;

/**
 * Confirmation returned after an active store proposal is cancelled.
 *
 * Endpoint  : POST /api/v1/internal/orders/{orderId}/cancel-proposal
 * Transition: AWAITING_CUSTOMER_RESPONSE → CANCELLED
 */
public record CancelProposalResponse(
        String orderId,
        /** Always {@code "CANCELLED"} on success. */
        String status,
        /** ISO-8601 timestamp of when the proposal was cancelled. */
        String cancelledAt
) {}
