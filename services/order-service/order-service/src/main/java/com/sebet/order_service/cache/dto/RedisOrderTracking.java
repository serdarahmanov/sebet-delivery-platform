package com.sebet.order_service.cache.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Cache 3 — order:tracking:{orderId}
 *
 * Live delivery state pushed by the live-tracking service every few seconds
 * as the driver moves.  Read by the WebSocket server to push updates to the
 * tracking screen: driver pin position, ETA chip countdown, progress steps.
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

    /** e.g. "out_for_delivery" */
    private String status;
    /** Live countdown shown on the map ETA chip. */
    private int etaMinutes;
    private double driverLat;
    private double driverLng;
    /** ISO-8601 timestamp of the last GPS push. */
    private String updatedAt;
}
