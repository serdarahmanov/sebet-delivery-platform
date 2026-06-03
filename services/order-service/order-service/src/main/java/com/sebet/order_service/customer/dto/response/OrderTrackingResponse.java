package com.sebet.order_service.customer.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Live GPS + ETA snapshot for a single order.
 *
 * Endpoint : GET /api/v1/orders/{orderId}/tracking
 * Source   : Cache 3 (live tracking) + Cache 4 (status)
 * Returns  : OrderTrackingResponse
 *
 * This endpoint serves as a fallback poll for clients that cannot
 * maintain a WebSocket connection. Clients with WebSocket support
 * should subscribe to /topic/orders/{orderId}/tracking instead.
 *
 * {@code driverLat}, {@code driverLng}, and {@code updatedAt} are null
 * when Cache 3 has expired (driver app silent for more than 5 minutes).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderTrackingResponse(
        String orderId,
        /** Current live status from Cache 4. */
        String status,
        /** Live countdown in minutes from Cache 3. */
        Integer etaMinutes,
        /** Driver latitude; null when tracking data has expired. */
        Double driverLat,
        /** Driver longitude; null when tracking data has expired. */
        Double driverLng,
        /** ISO-8601 timestamp of the last GPS push; null when Cache 3 has expired. */
        String updatedAt
) {}
