package com.sebet.order_service.customer.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A single step in the order progress timeline shown on the tracking screen
 * and delivered receipt.
 *
 * The API always returns all 4 steps in fixed order:
 *   1. PLACED
 *   2. PACKED
 *   3. ON_THE_WAY
 *   4. ARRIVED
 *
 * Steps that have not yet occurred have {@code occurredAt == null}.
 * The frontend renders filled vs empty circles based on this field alone —
 * no step-ordering or status-mapping logic is needed on the client.
 *
 * Example response:
 * <pre>
 * [
 *   { "status": "PLACED",     "label": "Placed",     "occurredAt": "2026-06-03T15:02:00Z" },
 *   { "status": "PACKED",     "label": "Packed",     "occurredAt": "2026-06-03T15:09:00Z" },
 *   { "status": "ON_THE_WAY", "label": "On the way", "occurredAt": "2026-06-03T15:15:00Z" },
 *   { "status": "ARRIVED",    "label": "Arrived",    "occurredAt": null }
 * ]
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TimelineStepResponse(

        /**
         * Machine-readable step identifier.
         * One of: {@code "PLACED"}, {@code "PACKED"}, {@code "ON_THE_WAY"}, {@code "ARRIVED"}.
         */
        String status,

        /**
         * Human-readable display label shown next to the step circle.
         * e.g. {@code "Placed"}, {@code "Packed"}, {@code "On the way"}, {@code "Arrived"}.
         */
        String label,

        /**
         * ISO-8601 timestamp shown next to the label, e.g. {@code "3:09 PM"}.
         * Null when the step has not yet been reached — renders as an empty/pending circle.
         */
        String occurredAt

) {}
