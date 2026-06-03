package com.sebet.order_service.customer.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sebet.order_service.customer.dto.response.shared.DeliveryAddressDto;
import com.sebet.order_service.customer.dto.response.shared.OrderItemDto;
import com.sebet.order_service.customer.dto.response.shared.StoreLocationDto;

import java.math.BigDecimal;
import java.util.List;



/**
 * Full payload for the active-order tracking screen — initial mount only.
 *
 * Endpoint : GET /api/v1/orders/active/{orderId}
 * Source   : Cache 2 (static snapshot) + Cache 4 (initial status)
 * Returns  : ActiveOrderDetailResponse
 *
 * {@code status} is included from Cache 4 so the progress stepper renders
 * immediately on mount without waiting for the WebSocket.
 * All subsequent status transitions, etaMinutes, and GPS coordinates
 * are delivered exclusively via WebSocket (/topic/orders/{orderId}/tracking).
 *
 * {@code driver} is null until the order reaches DRIVER_ASSIGNED status —
 * it is populated from Cache 2 which is updated once when a driver accepts.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActiveOrderDetailResponse(

        String orderId,
        /** Human-readable label, e.g. {@code "#GR-20455"}. */
        String orderNumber,

        /**
         * Initial status from Cache 4 — for the progress stepper first render.
         * e.g. {@code "CONFIRMED"}, {@code "OUT_FOR_DELIVERY"}.
         * WebSocket takes over live updates after mount.
         */
        String status,

        String storeName,
        String storeId,
        /** Store pin shown on the tracking map. */
        StoreLocationDto storeLocation,
        /** Drop-off pin shown on the tracking map. */
        DeliveryAddressDto deliveryAddress,

        List<OrderItemDto> items,

        /**
         * Static driver profile from Cache 2.
         * Null until a driver accepts the order (DRIVER_ASSIGNED or beyond).
         * Live GPS is NOT here — it arrives via WebSocket.
         */
        DriverDto driver,

        BigDecimal total,
        /** ISO-4217 currency code. */
        String currency,
        /** ISO-8601 order placement timestamp. */
        String createdAt,

        /**
         * Zero-padded 2-digit delivery verification code, e.g. {@code "04"}.
         * Non-null only when {@code status == "ARRIVED"}.
         * Source: Cache 7 (order:verification:{orderId}), merged at response-build time.
         * Null for all other statuses.
         *
         * Also available as a lightweight standalone read via
         * GET /api/v1/orders/{orderId}/verification-code for clients that
         * were offline when the WebSocket push was sent.
         */
        String verificationCode,

        /**
         * Full 4-step progress timeline — always contains all steps in order:
         * PLACED → PACKED → ON_THE_WAY → ARRIVED.
         * Steps not yet reached have {@code occurredAt == null}.
         * Source: Cache 6 (order:timeline:{orderId}).
         * WebSocket pushes new entries incrementally after mount.
         */
        List<TimelineStepResponse> timeline

) {

    /**
     * Static driver profile — written once when the driver accepts.
     * Phone is masked for display; the call button uses the masked value.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DriverDto(
            String driverId,
            /** First name + last initial, e.g. {@code "Junho K."}. */
            String name,
            /** Masked phone for the call button, e.g. {@code "+90 *** ** 47"}. */
            String phone,
            double rating,
            /** e.g. {@code "Toyota Corolla"}. */
            String vehicle,
            /** e.g. {@code "BG 4821 AB"}. */
            String plateNumber
    ) {}
}
