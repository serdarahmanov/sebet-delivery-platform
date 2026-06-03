package com.sebet.order_service.customer.dto.response;

import com.sebet.order_service.customer.dto.response.shared.DeliveryAddressDto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Single active-order card in the active-orders banner.
 *
 * Endpoint : GET /api/v1/orders/active
 * Source   : Cache 1 (active order ID set) → Cache 2 (order snapshot per ID)
 * Returns  : List&lt;ActiveOrderItemResponse&gt;
 *
 * Contains ONLY static data written once at order creation (Cache 2).
 * Live fields (status, etaMinutes, rider info, GPS) are intentionally absent —
 * the frontend subscribes to the WebSocket per orderId after receiving this list.
 */
public record ActiveOrderItemResponse(

        String orderId,
        /** Human-readable label, e.g. {@code "#GR-20455"}. */
        String orderNumber,

        String storeName,
        String storeId,

        /** First 3 item thumbnail URLs for the photo strip. */
        List<String> itemThumbnails,
        /** Total number of distinct line items; drives the "+N more" badge. */
        int itemCount,

        BigDecimal total,
        /** ISO-4217 currency code. */
        String currency,

        DeliveryAddressDto deliveryAddress,
        /** ISO-8601 order placement timestamp. */
        String createdAt

) {}
