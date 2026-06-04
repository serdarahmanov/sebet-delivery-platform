package com.sebet.order_service.customer.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sebet.order_service.shared.enums.ProductUnit;

import java.math.BigDecimal;
import java.util.List;

/**
 * The active change proposal made by the store for this order.
 *
 * Endpoint : GET /api/v1/orders/{orderId}/proposed-changes
 * Source   : Cache 8 (order:proposals:{orderId})
 * Returns  : OrderProposedChangesResponse
 *
 * Used when the customer's app was offline when the push notification was sent
 * and they need to fetch the proposal on re-launch.
 *
 * Returns 404 when the order has no active proposal (key expired, customer
 * already responded, or order is not in AWAITING_CUSTOMER_RESPONSE status).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderProposedChangesResponse(

        String orderId,
        /** ISO-8601 timestamp of when the store submitted the proposal. */
        String proposedAt,
        /** The items for which the store is proposing an alternative. */
        List<ProposedItem> changes

) {

    /**
     * A single item in the proposal, with both the original and available quantities
     * so the customer can see the exact shortfall.
     */
    public record ProposedItem(
            String productId,
            String productName,
            BigDecimal requestedQuantity,
            ProductUnit unit,
            /**
             * How much the store can provide.
             * Null means the item is completely out of stock — the customer
             * can only remove it or cancel the order.
             */
            BigDecimal availableQuantity
    ) {}
}
