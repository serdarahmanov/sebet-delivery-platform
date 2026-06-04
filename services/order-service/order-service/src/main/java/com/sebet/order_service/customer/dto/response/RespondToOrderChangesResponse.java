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
 *   {@code CONFIRMED}  — the customer accepted the changes (all or per-item) and
 *                        the order resumes preparation. The store is notified.
 *
 *   {@code CANCELLED}  — the customer chose to cancel the entire order, or all
 *                        proposed items were removed leaving an empty order.
 *                        Reason will be {@code USER_REQUESTED} or
 *                        {@code OUT_OF_STOCK} respectively.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RespondToOrderChangesResponse(

        String orderId,

        /**
         * The order's new status after the response was processed.
         * Either {@link OrderStatus#CONFIRMED} or {@link OrderStatus#CANCELLED}.
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
