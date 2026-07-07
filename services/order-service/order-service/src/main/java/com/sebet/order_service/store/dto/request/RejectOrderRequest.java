package com.sebet.order_service.store.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sebet.order_service.shared.enums.ProductUnit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request body for rejecting a PENDING order.
 *
 * Endpoint : POST /api/v1/store/orders/{orderId}/reject
 *
 * {@code reason} is mandatory and maps directly to
 * {@link com.sebet.order_service.customer.dto.response.CancelledOrderDetailResponse.CancellationReason}
 * on the customer side. Only the two store-initiated values are allowed here —
 * {@code PAYMENT_FAILED}, {@code TIMEOUT}, and {@code SYSTEM_ERROR} are
 * system-generated and must never be sent by the store.
 *
 * When {@code reason == OUT_OF_STOCK}, {@code outOfStockItems} must be non-empty.
 * Each entry carries both what the customer ordered and what the store can actually
 * provide, enabling the notification layer to offer the customer three choices:
 *   1. Accept the reduced quantity.
 *   2. Skip the item and proceed with the rest of the order.
 *   3. Cancel the order entirely.
 *
 * If {@code availableQuantity} is null on an entry, the item is completely
 * unavailable — only choices 2 and 3 are presented to the customer for that item.
 *
 * {@code note} is optional and for internal store use only; never surfaced to the customer.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RejectOrderRequest(

        /**
         * Machine-readable rejection reason.
         * {@code STORE_REJECTED} — generic rejection (store too busy, closing early, etc.)
         * {@code OUT_OF_STOCK}   — one or more specific items are unavailable; see {@code outOfStockItems}.
         */
        @NotNull
        RejectionReason reason,

        /**
         * Items that are out of stock — required when {@code reason == OUT_OF_STOCK},
         * null or empty otherwise.
         *
         * Each entry carries the original requested quantity alongside what the store
         * can actually offer, so the notification service can present the exact delta
         * to the customer without a separate catalogue lookup at notification time.
         */
        List<@NotNull @Valid OutOfStockItem> outOfStockItems,

        /**
         * Optional free-text note for the store's own records.
         * Never exposed outside the store service boundary.
         */
        String note

) {

    /**
     * The subset of cancellation reasons a store is permitted to use.
     * Mirrors the corresponding values in
     * {@link com.sebet.order_service.customer.dto.response.CancelledOrderDetailResponse.CancellationReason}.
     */
    public enum RejectionReason {
        /** Store is unable to fulfil the order for operational reasons. */
        STORE_REJECTED,
        /** One or more items are out of stock; detail provided in {@code outOfStockItems}. */
        OUT_OF_STOCK
    }

    /**
     * A single item the store cannot fully fulfil.
     *
     * Both {@code requestedQuantity} and {@code availableQuantity} are provided so
     * the notification layer can present the exact shortfall to the customer:
     *   - "You ordered 3 kg of tomatoes; we only have 2 kg."
     *   - "You ordered 5 pcs of sourdough bread; we have none left."
     *
     * {@code productName} is denormalised here so the notification service can render
     * a human-readable message without calling back to the product catalogue.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OutOfStockItem(

            @NotBlank
            String productId,

            /**
             * Display name at the time of rejection, e.g. {@code "Roma Tomatoes"}.
             * Stored alongside productId for notification rendering.
             */
            @NotBlank
            String productName,

            /**
             * The quantity the customer originally ordered, e.g. {@code 3}.
             */
            @NotNull @Positive
            BigDecimal requestedQuantity,

            /**
             * Unit of measure for both {@code requestedQuantity} and {@code availableQuantity}.
             */
            @NotNull
            ProductUnit unit,

            /**
             * How much the store can actually provide, in the same {@code unit}.
             *
             * Null  → item is completely unavailable; customer is offered "skip item" or "cancel order".
             * &gt; 0 → partial stock available; customer is additionally offered "accept reduced amount".
             *
             * Must not exceed {@code requestedQuantity} — enforced at the service layer.
             */
            @PositiveOrZero
            BigDecimal availableQuantity

    ) {}
}
