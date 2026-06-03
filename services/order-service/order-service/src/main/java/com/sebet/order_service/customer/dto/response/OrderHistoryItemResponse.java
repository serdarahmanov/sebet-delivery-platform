package com.sebet.order_service.customer.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

/**
 * Single row in the unified order history feed.
 *
 * Endpoint : GET /api/v1/orders
 * Source   : DB
 * Returns  : Page&lt;OrderHistoryItemResponse&gt;
 *
 * Scope: DELIVERED, CANCELLED, and SCHEDULED orders only.
 * Active orders are excluded — they are served by GET /api/v1/orders/active.
 *
 * The {@code route} field is the discriminator the frontend uses for two purposes:
 *  1. Which card layout to render  (scheduled chip / receipt / cancellation badge)
 *  2. Which detail endpoint to call when the row is tapped:
 *       SCHEDULED  →  GET /api/v1/orders/scheduled/{orderId}
 *       DELIVERED  →  GET /api/v1/orders/{orderId}
 *       CANCELLED  →  GET /api/v1/orders/cancelled/{orderId}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderHistoryItemResponse(

        String orderId,
        /** Human-readable label, e.g. {@code "#GR-20455"}. */
        String orderNumber,

        /** Routing discriminator — drives card rendering and deep-link target. */
        OrderDetailRoute route,

        String storeName,
        BigDecimal total,
        /** ISO-4217 currency code, e.g. {@code "TRY"}. */
        String currency,
        int itemCount,
        /** First 3 item thumbnail URLs for the photo strip. */
        List<String> itemThumbnails,
        /** ISO-8601 order placement timestamp. */
        String createdAt,

        /**
         * Scheduled delivery time.
         * Non-null only when {@code route == SCHEDULED}.
         */
        String scheduledFor,

        /**
         * Refund state badge.
         * Non-null only when {@code route == CANCELLED}.
         */
        RefundInfo refund

) {

    /**
     * Routing discriminator — one value per card type in the history feed.
     * The frontend maps each value to a detail endpoint and a card layout.
     * Active orders are never present in this feed.
     */
    public enum OrderDetailRoute {
        /** Order is awaiting its scheduled delivery window. */
        SCHEDULED,
        /** Order was successfully delivered. */
        DELIVERED,
        /** Order was cancelled. Refund info is present. */
        CANCELLED
    }

    /** Refund badge data — only present on CANCELLED cards. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RefundInfo(
            /** {@code "REFUND_PENDING"} or {@code "REFUNDED"}. */
            String status,
            /** ISO-8601 timestamp of refund completion; null while still pending. */
            String refundedAt
    ) {}
}
