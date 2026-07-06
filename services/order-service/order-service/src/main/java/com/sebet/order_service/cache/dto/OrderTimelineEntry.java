package com.sebet.order_service.cache.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Cache 6 — order:timeline:{orderId}  (LIST element / JSON)
 *
 * A single status-transition event appended to the timeline list
 * every time the order advances to the next customer-facing step.
 *
 * Internal statuses are mapped to customer-facing step names before storage:
 *   PENDING              → PLACED
 *   CONFIRMED /
 *   READY_FOR_PICKUP     → PACKED
 *   OUT_FOR_DELIVERY     → ON_THE_WAY
 *   DELIVERED            → ARRIVED
 *
 * Stored as an element in a Redis LIST via RPUSH so insertion order
 * is preserved without explicit sorting.
 *
 * The list TTL is 48 hours — matches Cache 2 (order snapshot).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderTimelineEntry {

    /**
     * Customer-facing step name.
     * One of: {@code "PLACED"}, {@code "PACKED"}, {@code "ON_THE_WAY"}, {@code "ARRIVED"}.
     */
    private String status;

    /** ISO-8601 timestamp of when this step was reached. */
    private String occurredAt;
}
