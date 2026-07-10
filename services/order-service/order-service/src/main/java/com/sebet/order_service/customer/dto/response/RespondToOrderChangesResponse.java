package com.sebet.order_service.customer.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sebet.order_service.shared.enums.OrderStatus;

/**
 * Result of the customer's response to a store change proposal.
 *
 * Endpoint : POST /api/v1/orders/{orderId}/respond-to-changes
 * Returns  : RespondToOrderChangesResponse
 *
 * Two outcomes are possible:
 *
 *   {@code AWAITING_CUSTOMER_RESPONSE} — the customer accepted the changes (ACCEPT_ALL or
 *                        ACCEPT_WITH_MODIFICATIONS). An {@code OrderProposalAccepted} event
 *                        is published; the order stays in this status until the promo service
 *                        recalculates discounts and calls back via the internal update endpoint.
 *
 *   {@code CANCELLED}  — the customer chose CANCEL_ORDER.
 *                        Reason will be {@code USER_REQUESTED}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RespondToOrderChangesResponse(

        String orderId,

        /**
         * The order's status after the response was processed.
         * Either {@link OrderStatus#AWAITING_CUSTOMER_RESPONSE} (accept) or
         * {@link OrderStatus#CANCELLED} (cancel).
         */
        OrderStatus status,

        /**
         * ISO-8601 timestamp of when the response was processed.
         */
        String resolvedAt,

        /**
         * Human-readable summary of the outcome, e.g.
         * {@code "Order resumed with 2 of 3 proposed items accepted."} or
         * {@code "Order cancelled as requested."}.
         * Intended for display in the confirmation screen, not for programmatic use.
         */
        String message

) {}
