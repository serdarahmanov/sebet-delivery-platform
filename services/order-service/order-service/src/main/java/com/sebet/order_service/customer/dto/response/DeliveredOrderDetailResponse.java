package com.sebet.order_service.customer.dto.response;

import com.sebet.order_service.customer.dto.response.shared.DeliveryAddressDto;
import com.sebet.order_service.customer.dto.response.shared.OrderItemDto;
import com.sebet.order_service.customer.dto.response.shared.PricingDto;

import java.util.List;


/**
 * Receipt view for a successfully delivered order.
 *
 * Endpoint : GET /api/v1/orders/{orderId}
 * Source   : DB
 * Returns  : DeliveredOrderDetailResponse
 */
public record DeliveredOrderDetailResponse(

        String orderId,
        /** Human-readable label, e.g. {@code "#GR-20455"}. */
        String orderNumber,

        String storeName,
        String storeId,

        DeliveryAddressDto deliveryAddress,
        List<OrderItemDto> items,
        PricingDto pricing,

        /** ISO-4217 currency code. */
        String currency,
        /** ISO-8601 order placement timestamp. */
        String createdAt,
        /** ISO-8601 actual delivery timestamp. */
        String deliveredAt,

        /**
         * Complete 4-step timeline — all steps filled with timestamps.
         * Source: DB (order_status_history table).
         * Shown on the receipt screen as a read-only history strip.
         */
        List<TimelineStepResponse> timeline

) {}
