package com.sebet.order_service.cache.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Cache 3 — order:tracking:{orderId}
 *
 * Live delivery state written to Cache 3 from two sources:
 *   - Driver app  : pushes raw GPS coordinates via PUT /api/v1/driver/orders/{orderId}/location
 *   - Tracking service : calculates ETA from coordinates and writes it back here
 * Read by the WebSocket server to push updates to the customer's tracking screen.
 *
 * Kept separate from RedisOrder so frequent GPS writes do not touch the
 * larger static order blob.
 *
 * TTL: 5 minutes — sliding, reset on every write.
 * An expired key means the driver app has gone silent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RedisOrderTracking {

    /**
     * Driver movement state — more granular than the order lifecycle status in Cache 4.
     * e.g. "en_route_to_store", "at_store", "en_route_to_customer", "at_customer".
     * Written by the tracking service via the DriverLocationUpdatedEvent.
     */
    private String movementStatus;
    /** Live countdown shown on the map ETA chip. */
    private int etaMinutes;
    private double driverLat;
    private double driverLng;
    /** ISO-8601 timestamp of the last GPS push. */
    private String updatedAt;
}
