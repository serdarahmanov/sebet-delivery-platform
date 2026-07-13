package com.sebet.order_service.customer.dto.response;

import com.sebet.order_service.customer.dto.response.shared.DeliveryAddressDto;
import com.sebet.order_service.customer.dto.response.shared.OrderItemDto;
import com.sebet.order_service.customer.dto.response.shared.PricingDto;

import java.util.List;

/**
 * Detail view for a scheduled (future) order.
 *
 * Endpoint : GET /api/v1/orders/scheduled/{orderId}
 * Source   : DB
 * Returns  : ScheduledOrderDetailResponse
 */
public record ScheduledOrderDetailResponse(

        String orderId,
        /** Human-readable label, e.g. {@code "#GR-20455"}. */
        String orderNumber,

        /** ISO-8601 start of the requested delivery window. */
        String scheduledWindowStart,

        /** ISO-8601 end of the requested delivery window (start + configured window duration). */
        String scheduledWindowEnd,

        String storeName,
        String storeId,

        DeliveryAddressDto deliveryAddress,
        List<OrderItemDto> items,
        PricingDto pricing,

        /** ISO-4217 currency code. */
        String currency,

        /**
         * Whether the customer is still within the cancellation window.
         * Computed by the service layer (e.g. more than 1 hour before {@code scheduledFor}).
         */
        boolean canCancel,

        /** ISO-8601 order placement timestamp. */
        String createdAt

) {}
