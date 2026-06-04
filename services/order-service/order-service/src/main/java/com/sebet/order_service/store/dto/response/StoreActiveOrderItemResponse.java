package com.sebet.order_service.store.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sebet.order_service.customer.dto.response.shared.OrderItemDto;
import com.sebet.order_service.shared.enums.OrderStatus;

import java.util.List;

/**
 * Single active-order card on the store's kitchen dashboard.
 *
 * Endpoint : GET /api/v1/store/orders/active
 * Source   : Cache 1b (store active order ID set) → Cache 2 (order snapshot) + Cache 4 (status)
 * Returns  : List&lt;StoreActiveOrderItemResponse&gt;
 *
 * An order enters this list as soon as it is placed (PENDING) and is removed
 * once it reaches DELIVERED or CANCELLED.  Scheduled orders only appear here
 * 30 minutes before their requested delivery time — before that window they
 * are served by the dedicated scheduled-orders list endpoint.
 *
 * The full item list is included (not thumbnails) because store staff need to
 * see exactly what to prepare.  Pricing and delivery address are intentionally
 * excluded — the kitchen does not need them on this card.
 *
 * Pickup matching between store and courier is done via the order number, which
 * is visible on both the store's receipt and the courier's app.
 *
 * {@code driver} is null until DRIVER_ASSIGNED.  From that point onward the
 * store can see who is coming to collect the order.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StoreActiveOrderItemResponse(

        String orderId,
        /** Human-readable label, e.g. {@code "#GR-20455"}. */
        String orderNumber,

        /**
         * Current order status.
         * Active statuses: {@code PENDING}, {@code CONFIRMED}, {@code READY_FOR_PICKUP},
         * {@code DRIVER_ASSIGNED}, {@code OUT_FOR_DELIVERY}, {@code ARRIVED}.
         *
         * {@code AWAITING_CUSTOMER_RESPONSE} also appears here when the store has
         * submitted a change proposal and is waiting for the customer to decide.
         * The kitchen card should render a "waiting for customer" badge in this state
         * and disable the {@code /ready} action until the order resumes ({@code CONFIRMED}).
         */
        OrderStatus status,

        /** Full item list for kitchen preparation. */
        List<OrderItemDto> items,
        /** Total number of distinct line items. */
        int itemCount,

        /** ISO-8601 timestamp when the order was placed. */
        String createdAt,

        /**
         * Courier's profile — non-null from {@code DRIVER_ASSIGNED} onward.
         * Lets store staff know who is coming to collect the order.
         */
        DriverDto driver

) {

    /**
     * Static courier profile displayed to store staff from DRIVER_ASSIGNED onward.
     */
    public record DriverDto(
            String driverId,
            /** e.g. {@code "Junho K."} */
            String name,
            String phone,
            double rating,
            /** e.g. {@code "Toyota Corolla"} */
            String vehicle,
            /** e.g. {@code "BG 4821 AB"} */
            String plateNumber
    ) {}
}
