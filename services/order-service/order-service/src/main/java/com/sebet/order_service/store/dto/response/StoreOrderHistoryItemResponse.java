package com.sebet.order_service.store.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sebet.order_service.shared.enums.OrderCancelledBy;
import com.sebet.order_service.shared.enums.OrderCancellationReason;
import com.sebet.order_service.store.dto.response.shared.CustomerInfoDto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Single row in the store's paginated order history feed.
 *
 * Endpoint : GET /api/v1/store/orders
 * Source   : DB
 * Returns  : Page&lt;StoreOrderHistoryItemResponse&gt;
 *
 * Scope: DELIVERED and CANCELLED orders only.
 * Active orders are excluded — served by GET /api/v1/store/orders/active.
 *
 * The {@code route} discriminator tells the frontend which card layout to
 * render when a row is tapped.  Both routes resolve to the same detail
 * endpoint (GET /api/v1/store/orders/{orderId}); the status field inside
 * that response drives the detail view.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StoreOrderHistoryItemResponse(

        String orderId,
        /** Human-readable label, e.g. {@code "#GR-20455"}. */
        String orderNumber,

        /** Card-type discriminator — drives layout and icon on the history row. */
        OrderDetailRoute route,

        CustomerInfoDto customer,

        /** First 3 item thumbnail URLs for the photo strip. */
        List<String> itemThumbnails,
        /** Total number of distinct line items; drives the "+N more" badge. */
        int itemCount,

        BigDecimal grandTotal,
        /** ISO-4217 currency code, e.g. {@code "TRY"}. */
        String currency,

        /** ISO-8601 order placement timestamp. */
        String createdAt,

        /**
         * ISO-8601 actual delivery timestamp.
         * Non-null only when {@code route == DELIVERED}.
         */
        String deliveredAt,

        /**
         * ISO-8601 cancellation timestamp.
         * Non-null only when {@code route == CANCELLED}.
         */
        String cancelledAt,

        /**
         * Cancellation detail badge.
         * Non-null only when {@code route == CANCELLED}.
         */
        CancellationInfo cancellation

) {

    public enum OrderDetailRoute {
        DELIVERED,
        CANCELLED
    }

    /**
     * Cancellation summary shown on the CANCELLED history card.
     */
    public record CancellationInfo(
            OrderCancelledBy cancelledBy,
            OrderCancellationReason reason
    ) {}
}
