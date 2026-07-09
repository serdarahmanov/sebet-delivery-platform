package com.sebet.order_service.store.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sebet.order_service.shared.enums.ProductUnit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request body for proposing alternative item quantities on a CONFIRMED order.
 *
 * Endpoint : POST /api/v1/store/orders/{orderId}/propose-changes
 *
 * Used when the store has already accepted the order (CONFIRMED) but discovers
 * during preparation that one or more items have stock shortfalls.  Instead of
 * cancelling the order outright, the store submits this proposal so the customer
 * can decide how to proceed.
 *
 * The order transitions to {@code AWAITING_CUSTOMER_RESPONSE} and a push
 * notification is sent to the customer showing:
 *   - Items with a reduced available quantity  (availableQuantity &gt; 0 but &lt; requestedQuantity)
 *   - Items that are completely out of stock   (availableQuantity == null)
 *
 * Customer choices per item:
 *   1. Accept the proposed (reduced) quantity.
 *   2. Remove the item from the order entirely and continue.
 *   3. Cancel the whole order.
 *
 * {@code changes} must contain at least one entry.
 * All productIds must belong to the order being modified — validated at the service layer.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProposeOrderChangesRequest(

        /**
         * List of items with stock shortfalls.
         * Each entry carries both the originally requested quantity and what the
         * store can actually provide, so the notification can show the exact delta.
         */
        @NotEmpty @Valid
        List<ProposedItemChange> changes

) {

    /**
     * A single item for which the store is proposing an alternative quantity.
     *
     * {@code productName} is denormalised here so the notification service can
     * render a human-readable message without calling back to the product catalogue.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProposedItemChange(

            @NotBlank
            String productId,

            /**
             * Display name at the time of the proposal, e.g. {@code "Granny Smith Apples"}.
             */
            @NotBlank
            String productName,

            /**
             * The quantity the customer originally ordered, e.g. {@code 3}.
             */
            @NotNull @Positive
            BigDecimal requestedQuantity,

            /**
             * Unit of measure for both quantities.
             */
            @NotNull
            ProductUnit unit,

            /**
             * How much the store can actually provide, in the same {@code unit}.
             *
             * Null  → item is completely out of stock; customer sees "remove or cancel".
             * &gt; 0 → partial stock; customer additionally sees "accept X instead of Y".
             * 0 is not valid — use null when nothing is available.
             *
             * Must be strictly less than {@code requestedQuantity} — if the store has
             * equal or more stock the item should not appear in this list.
             * Enforced at the service layer.
             */
            @Positive
            BigDecimal availableQuantity

    ) {}
}
