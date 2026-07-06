package com.sebet.order_service.driver.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sebet.order_service.shared.enums.OrderStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * Delivery detail payload for the driver app.
 *
 * Endpoint : GET /api/v1/driver/orders/{orderId}
 * Source   : Cache 2 (order snapshot) + Cache 4 (current status)
 *
 * Contains everything the driver needs to complete the delivery:
 * the store's location to navigate to for pickup, the customer's drop-off
 * address for delivery, the item list for pickup verification, and
 * the estimated delivery time.
 *
 * Customer contact details are intentionally minimal — phone is masked
 * for display on the call button.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DriverOrderDetailResponse(

        String orderId,
        /** Human-readable label, e.g. {@code "#GR-20455"}. */
        String orderNumber,

        /** Current status from Cache 4. */
        OrderStatus status,

        /** Store the driver navigates to for collection. */
        PickupPoint pickup,

        /** Customer address the driver navigates to for delivery. */
        DropoffPoint dropoff,

        /** Items to verify against the bag at pickup. */
        List<ItemLine> items,

        /** ISO-8601 estimated delivery timestamp; null until dispatch assigns an ETA. */
        String estimatedDeliveryAt,

        /** ISO-8601 order placement timestamp. */
        String createdAt

) {

    /**
     * Store location for pickup navigation.
     * Coordinates are always present; street address is not stored in the order
     * snapshot and must be fetched from the store catalogue if needed.
     */
    public record PickupPoint(
            String storeId,
            String storeName,
            double lat,
            double lng
    ) {}

    /**
     * Customer drop-off location for delivery navigation.
     * {@code apartment} is null when the customer did not provide one.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DropoffPoint(
            String street,
            String city,
            /** May be null. */
            String apartment,
            double lat,
            double lng
    ) {}

    /** Single item line shown for bag verification at store pickup. */
    public record ItemLine(
            String productId,
            String name,
            BigDecimal quantity,
            BigDecimal unitPrice
    ) {}
}
