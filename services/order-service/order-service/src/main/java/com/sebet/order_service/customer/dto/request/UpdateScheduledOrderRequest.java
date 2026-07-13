package com.sebet.order_service.customer.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Partial-update request for a scheduled order.
 *
 * Endpoint : PATCH /api/v1/orders/scheduled/{orderId}
 *
 * At least one of {@code scheduledWindowStart}, {@code newAddress}, or
 * {@code phoneNumber} must be non-null; sending an empty body returns 400.
 *
 * The request is rejected (409) when the order is outside the modification
 * window (i.e. {@code canCancel == false} on ScheduledOrderDetailResponse).
 */
public record UpdateScheduledOrderRequest(

        /**
         * New delivery window start in ISO-8601 format (e.g. "2026-07-20T14:00:00+03:00").
         * Must be in the future beyond the configured min-lead-time, aligned to the
         * configured slot interval, and fall within the store's working hours.
         * Null means no change to the delivery time.
         */
        String scheduledWindowStart,

        /**
         * Full replacement delivery address. All fields except {@code label} are required
         * when this object is present.
         * Null means no change to the delivery address.
         */
        @Valid
        NewDeliveryAddress newAddress,

        /**
         * Contact phone number for the driver at drop-off.
         * Null means no change to the phone number.
         */
        @Size(max = 30)
        String phoneNumber

) {

    public record NewDeliveryAddress(

            /** Optional human-readable label (e.g. "Home", "Office"). */
            @Size(max = 100)
            String label,

            @NotBlank
            @Size(max = 255)
            String street,

            @NotBlank
            @Size(max = 100)
            String city,

            @NotNull
            BigDecimal lat,

            @NotNull
            BigDecimal lng
    ) {}
}
