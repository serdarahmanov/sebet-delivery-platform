package com.sebet.order_service.store.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sebet.order_service.customer.dto.response.TimelineStepResponse;
import com.sebet.order_service.customer.dto.response.shared.DeliveryAddressDto;
import com.sebet.order_service.customer.dto.response.shared.OrderItemDto;
import com.sebet.order_service.customer.dto.response.shared.PricingDto;
import com.sebet.order_service.shared.enums.OrderCancelledBy;
import com.sebet.order_service.shared.enums.OrderCancellationReason;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ProductUnit;
import com.sebet.order_service.store.dto.response.shared.CustomerInfoDto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Full order detail view for the store.
 *
 * Endpoint : GET /api/v1/store/orders/{orderId}
 * Source   : Cache 2 + Cache 4 + Cache 6 for active orders; DB for completed/cancelled.
 *            Cache 8 (proposals) merged when {@code status == AWAITING_CUSTOMER_RESPONSE}.
 * Returns  : StoreOrderDetailResponse
 *
 * Works for any order status — active, delivered, or cancelled.
 *
 * {@code driver}          — null until a driver is assigned.
 * {@code cancellation}    — non-null only when {@code status == CANCELLED}.
 * {@code pendingProposal} — non-null only when {@code status == AWAITING_CUSTOMER_RESPONSE};
 *                           contains the exact proposal the store submitted so staff can
 *                           review what is pending the customer's decision.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StoreOrderDetailResponse(

        String orderId,
        /** Human-readable label, e.g. {@code "#GR-20455"}. */
        String orderNumber,

        OrderStatus status,

        CustomerInfoDto customer,
        DeliveryAddressDto deliveryAddress,

        List<OrderItemDto> items,
        PricingDto pricing,
        /** ISO-4217 currency code. */
        String currency,

        /** ISO-8601 order placement timestamp. */
        String createdAt,
        /** ISO-8601 estimated delivery timestamp; null for immediate orders before dispatch. */
        String estimatedDeliveryAt,

        /**
         * Full 4-step progress timeline.
         * Steps not yet reached have {@code occurredAt == null}.
         * Source: Cache 6 for active orders, DB for historical.
         */
        List<TimelineStepResponse> timeline,

        /**
         * Assigned courier's profile — non-null once a driver is assigned.
         */
        DriverDto driver,

        /**
         * The active change proposal awaiting the customer's response.
         * Non-null only when {@code status == AWAITING_CUSTOMER_RESPONSE}.
         * Source: Cache 8 (order:proposals:{orderId}).
         *
         * Lets store staff review exactly what they submitted and see
         * that the order is paused pending the customer's decision.
         */
        PendingProposal pendingProposal,

        /**
         * Cancellation detail — non-null only when {@code status == CANCELLED}.
         */
        CancellationDetail cancellation

) {

    /**
     * Static courier profile shown in the order detail once a driver is assigned.
     */
    public record DriverDto(
            String driverId,
            String name,
            String phone,
            double rating,
            String vehicle,
            String plateNumber
    ) {}

    /**
     * The change proposal currently awaiting the customer's response.
     * Sourced from Cache 8 and merged into the detail response at read time.
     */
    public record PendingProposal(
            /** ISO-8601 timestamp of when the store submitted the proposal. */
            String proposedAt,
            /** The items for which the store proposed an alternative quantity. */
            List<ProposedItemDetail> items
    ) {

        /**
         * A single item within the pending proposal.
         *
         * {@code availableQuantity == null} means the item is completely out of stock.
         * {@code availableQuantity > 0} means partial stock is available.
         */
        public record ProposedItemDetail(
                String productId,
                String productName,
                BigDecimal requestedQuantity,
                ProductUnit unit,
                BigDecimal availableQuantity
        ) {}
    }

    /**
     * Cancellation detail shown when the order was not fulfilled.
     */
    public record CancellationDetail(
            OrderCancelledBy cancelledBy,
            OrderCancellationReason reason,
            /** ISO-8601 cancellation timestamp. */
            String cancelledAt
    ) {}
}
