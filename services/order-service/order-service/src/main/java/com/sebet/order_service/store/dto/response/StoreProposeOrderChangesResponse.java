package com.sebet.order_service.store.dto.response;

import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ProductUnit;

import java.math.BigDecimal;
import java.util.List;

/**
 * Confirmation returned after the store successfully submits a change proposal.
 *
 * Endpoint : POST /api/v1/store/orders/{orderId}/propose-changes
 * Transition: CONFIRMED → AWAITING_CUSTOMER_RESPONSE
 * Returns  : StoreProposeOrderChangesResponse
 *
 * The store must have already accepted the order (CONFIRMED) before proposing
 * changes — stock shortfalls are typically discovered during preparation, not at
 * acceptance time.
 *
 * The response echoes the stored proposal so the store can confirm exactly what
 * was submitted.  A push notification is dispatched to the customer at this point.
 *
 * Returns 409 Conflict  if the order is not in CONFIRMED status.
 * Returns 404 Not Found if the order does not belong to this store.
 */
public record StoreProposeOrderChangesResponse(

        String orderId,
        /** Always {@link OrderStatus#AWAITING_CUSTOMER_RESPONSE} on a successful response. */
        OrderStatus status,

        /** ISO-8601 timestamp of when the proposal was recorded and the notification was sent. */
        String proposedAt,

        /** The stored change proposals, echoed back for confirmation. */
        List<ProposedItemChangeResult> changes

) {

    /**
     * A single stored item proposal, echoed in the response.
     */
    public record ProposedItemChangeResult(
            String productId,
            String productName,
            BigDecimal requestedQuantity,
            ProductUnit unit,
            /** Null when the item is completely out of stock. */
            BigDecimal availableQuantity
    ) {}
}
