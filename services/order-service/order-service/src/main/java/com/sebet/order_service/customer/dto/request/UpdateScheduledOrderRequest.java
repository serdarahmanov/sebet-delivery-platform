package com.sebet.order_service.customer.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Partial-update request for a scheduled order.
 *
 * Endpoint : PATCH /api/v1/orders/scheduled/{orderId}
 *
 * Both fields are optional — send only the ones being changed.
 * At least one field must be non-null; sending an empty body returns 400.
 * The request is rejected (409) when the order is outside the modification window
 * (i.e. {@code canCancel == false} on {@link com.sebet.order_service.customer.dto.response.ScheduledOrderDetailResponse}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpdateScheduledOrderRequest(

        /**
         * New scheduled delivery time in ISO-8601 format.
         * Null means no change to the delivery time.
         * Must be in the future and within the store's scheduling window.
         */
        String scheduledFor,

        /**
         * ID of the customer's saved address to use as the new drop-off point.
         * Null means no change to the delivery address.
         */
        String addressId

) {}
