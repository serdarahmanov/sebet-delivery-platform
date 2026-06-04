package com.sebet.order_service.store.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for cancelling an already-accepted order.
 *
 * Endpoint : POST /api/v1/store/orders/{orderId}/cancel
 *
 * Valid when the order is in one of these post-acceptance statuses:
 *   {@code CONFIRMED}                  — store accepted but can no longer fulfil.
 *   {@code AWAITING_CUSTOMER_RESPONSE} — store proposed changes but must now cancel
 *                                        before the customer responds.
 *
 * Semantically distinct from POST /reject, which is a pre-acceptance refusal
 * on a PENDING order.  This endpoint covers situations where the store committed
 * to the order and is now backing out, warranting a different notification to
 * the customer and different reason codes.
 *
 * {@code note} is optional and for internal store use only; never surfaced to the customer.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StoreCancelOrderRequest(

        /**
         * Post-acceptance cancellation reason.
         * Only the values defined in {@link CancellationReason} are accepted here —
         * pre-acceptance reasons ({@code STORE_REJECTED}, {@code OUT_OF_STOCK}) are
         * not valid for this endpoint and are rejected at the service layer.
         */
        @NotNull
        CancellationReason reason,

        /**
         * Optional free-text note for the store's own records.
         * Never exposed outside the store service boundary.
         */
        String note

) {

    /**
     * Reasons a store may cancel an order it has already accepted.
     *
     * Each value maps to a corresponding {@link com.sebet.order_service.shared.enums.OrderCancellationReason}
     * that is persisted and used for customer notifications and refund logic.
     */
    public enum CancellationReason {

        /**
         * Store went offline or closed unexpectedly after accepting the order.
         * Maps to {@link com.sebet.order_service.shared.enums.OrderCancellationReason#STORE_CLOSED}.
         */
        STORE_CLOSED,

        /**
         * Store cannot complete preparation for operational reasons unrelated to stock
         * (e.g. equipment failure, unexpected staff shortage).
         * Maps to {@link com.sebet.order_service.shared.enums.OrderCancellationReason#STORE_UNABLE_TO_FULFIL}.
         */
        STORE_UNABLE_TO_FULFIL
    }
}
