package com.sebet.order_service.customer.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request body for the customer's response to a store's change proposal.
 *
 * Endpoint : POST /api/v1/orders/{orderId}/respond-to-changes
 *
 * When the store proposes alternative quantities (one or more items understocked),
 * the customer receives a push notification and must respond via this endpoint.
 *
 * Three top-level decisions are available via {@link GlobalDecision}:
 *
 *   {@code ACCEPT_ALL}                — accept every proposed quantity as-is.
 *                                       {@code itemDecisions} must be empty.
 *
 *   {@code ACCEPT_WITH_MODIFICATIONS} — make a per-item choice for each proposed item.
 *                                       {@code itemDecisions} must contain one entry
 *                                       per proposed product ID.
 *
 *   {@code CANCEL_ORDER}              — cancel the order entirely.
 *                                       {@code itemDecisions} must be empty.
 *                                       Results in {@code CANCELLED / USER_REQUESTED}.
 *
 * Per-item actions ({@link ItemDecision.ItemAction}):
 *
 *   {@code ACCEPT_PROPOSED_QUANTITY}  — take the quantity the store offered.
 *   {@code REQUEST_CUSTOM_QUANTITY}   — request a different amount, provided in
 *                                       {@link ItemDecision#customQuantity}.
 *                                       Must be &gt; 0 and &le; the store's
 *                                       {@code availableQuantity}.
 *   {@code REMOVE_ITEM}               — drop this item and continue without it.
 *
 * Cross-field validation (global decision vs item decisions vs custom quantities)
 * is enforced at the service layer.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RespondToOrderChangesRequest(

        @NotNull
        GlobalDecision globalDecision,

        /**
         * Per-item decisions — required only when
         * {@code globalDecision == ACCEPT_WITH_MODIFICATIONS}.
         * Must contain exactly one entry for every productId in the active proposal.
         */
        @Valid
        List<ItemDecision> itemDecisions

) {

    /** The customer's top-level response to the store's proposal. */
    public enum GlobalDecision {
        /** Accept all proposed quantities without changes. */
        ACCEPT_ALL,
        /** Make a separate choice for each proposed item. */
        ACCEPT_WITH_MODIFICATIONS,
        /** Cancel the entire order. */
        CANCEL_ORDER
    }

    /** The customer's choice for a single proposed item. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ItemDecision(

            @NotBlank
            String productId,

            @NotNull
            ItemAction action,

            /**
             * Custom quantity requested by the customer.
             * Required when {@code action == REQUEST_CUSTOM_QUANTITY}, null otherwise.
             *
             * Must satisfy: {@code 0 < customQuantity <= store's availableQuantity}.
             * Example: store proposed 3 kg, customer wants only 2 kg → {@code customQuantity = 2}.
             * Enforced at the service layer.
             */
            @Positive
            BigDecimal customQuantity

    ) {

        public enum ItemAction {
            /** Accept exactly the quantity the store can provide. */
            ACCEPT_PROPOSED_QUANTITY,
            /**
             * Request a different quantity, specified in {@code customQuantity}.
             * Must be less than or equal to the store's available quantity.
             */
            REQUEST_CUSTOM_QUANTITY,
            /** Remove this item from the order entirely and continue without it. */
            REMOVE_ITEM
        }
    }
}
