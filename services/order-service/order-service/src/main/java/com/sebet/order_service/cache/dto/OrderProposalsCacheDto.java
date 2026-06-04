package com.sebet.order_service.cache.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sebet.order_service.shared.enums.ProductUnit;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cache 8 — order:proposals:{orderId}  (STRING / JSON)
 *
 * Stores the set of item-level change proposals submitted by the store when an
 * order transitions to {@code AWAITING_CUSTOMER_RESPONSE}.
 *
 * Lifecycle:
 *   Written by : POST /api/v1/store/orders/{orderId}/propose-changes
 *   Read by    : GET  /api/v1/orders/{orderId}/proposed-changes  (customer views the proposal)
 *                POST /api/v1/orders/{orderId}/respond-to-changes (customer responds)
 *   Cleared by : customer response (accepted or cancelled) or timeout job
 *
 * TTL: 1 hour — if the customer has not responded within 1 hour the timeout
 * job cancels the order with reason {@code AWAITING_CUSTOMER_RESPONSE_TIMEOUT}
 * and this key will have already expired or will be explicitly deleted.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderProposalsCacheDto {

    private String orderId;

    /** ISO-8601 timestamp of when the proposal was submitted by the store. */
    private String proposedAt;

    /** The items for which the store is proposing an alternative quantity. */
    private List<ProposedItem> items;

    /**
     * A single item proposal.
     *
     * {@code availableQuantity == null} means the item is completely out of stock.
     * {@code availableQuantity > 0} means partial stock is available.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProposedItem {
        private String productId;
        private String productName;
        private BigDecimal requestedQuantity;
        private ProductUnit unit;
        /** Null when completely out of stock. */
        private BigDecimal availableQuantity;
    }
}
