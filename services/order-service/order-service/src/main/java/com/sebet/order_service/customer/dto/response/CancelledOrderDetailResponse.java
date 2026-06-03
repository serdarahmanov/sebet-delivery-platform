package com.sebet.order_service.customer.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sebet.order_service.customer.dto.response.shared.DeliveryAddressDto;
import com.sebet.order_service.customer.dto.response.shared.OrderItemDto;
import com.sebet.order_service.customer.dto.response.shared.PricingDto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Detail view for a cancelled order — shows what was ordered and refund status.
 *
 * Endpoint : GET /api/v1/orders/cancelled/{orderId}
 * Source   : DB
 * Returns  : CancelledOrderDetailResponse
 */
public record CancelledOrderDetailResponse(

        String orderId,
        /** Human-readable label, e.g. {@code "#GR-20455"}. */
        String orderNumber,

        String storeName,
        String storeId,

        DeliveryAddressDto deliveryAddress,
        /** Items as they were at the time of the order — for the "what you ordered" section. */
        List<OrderItemDto> items,
        /** Pricing at order time — used to show what was charged before refund. */
        PricingDto pricing,

        /** ISO-4217 currency code. */
        String currency,
        /** ISO-8601 cancellation timestamp. */
        String cancelledAt,

        /** Who initiated the cancellation. */
        CancelledBy cancelledBy,
        /** Machine-readable reason code for the cancellation. */
        CancellationReason cancellationReason,

        /** Refund state — always present for cancelled orders. */
        RefundInfo refund,

        /** ISO-8601 order placement timestamp. */
        String createdAt

) {

    public enum CancelledBy {
        USER,
        STORE,
        SYSTEM
    }

    public enum CancellationReason {
        USER_REQUESTED,
        STORE_REJECTED,
        PAYMENT_FAILED,
        OUT_OF_STOCK,
        TIMEOUT,
        SYSTEM_ERROR
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RefundInfo(
            /** {@code "REFUND_PENDING"} or {@code "REFUNDED"}. */
            String status,
            /** Exact refund amount issued (may differ from grandTotal due to partial refunds). */
            BigDecimal amount,
            /** ISO-8601 timestamp of refund completion; null while still pending. */
            String refundedAt
    ) {}
}
