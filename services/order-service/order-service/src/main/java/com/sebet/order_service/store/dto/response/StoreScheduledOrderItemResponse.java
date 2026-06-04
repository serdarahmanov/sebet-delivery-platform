package com.sebet.order_service.store.dto.response;

import com.sebet.order_service.customer.dto.response.shared.OrderItemDto;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.store.dto.response.shared.CustomerInfoDto;

import java.util.List;

/**
 * Single row in the store's upcoming scheduled-orders list.
 *
 * Endpoint : GET /api/v1/store/orders/scheduled
 * Source   : Cache 1c (store:scheduled_orders:{storeId} ZSET) → Cache 2 (order snapshot per ID)
 * Returns  : List&lt;StoreScheduledOrderItemResponse&gt; sorted ascending by {@code scheduledFor}
 *
 * Only orders whose {@code scheduledFor} time is more than 30 minutes away appear
 * here.  Once an order enters the 30-minute window it is transitioned to PENDING
 * and moves to the active queue (GET /api/v1/store/orders/active).
 *
 * This list is intended for stock planning — store staff can review what items
 * will be needed throughout the day before the orders become active.
 * Pricing is excluded; delivery address is excluded (not needed for planning).
 */
public record StoreScheduledOrderItemResponse(

        String orderId,
        /** Human-readable label, e.g. {@code "#GR-20455"}. */
        String orderNumber,

        /** Always {@link OrderStatus#SCHEDULED} for entries in this list. */
        OrderStatus status,

        /**
         * ISO-8601 requested delivery time.
         * The list is sorted ascending by this field (soonest first).
         */
        String scheduledFor,

        CustomerInfoDto customer,

        /** Full item list for stock planning purposes. */
        List<OrderItemDto> items,
        /** Total number of distinct line items. */
        int itemCount,

        /** ISO-8601 timestamp when the order was originally placed. */
        String createdAt

) {}
