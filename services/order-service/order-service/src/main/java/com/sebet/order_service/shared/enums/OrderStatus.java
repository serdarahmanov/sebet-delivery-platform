package com.sebet.order_service.shared.enums;

/**
 * All possible statuses an order can hold during its lifecycle.
 *
 * ── Normal flow ──────────────────────────────────────────────────────────────
 *   PENDING → CONFIRMED → READY_FOR_PICKUP → OUT_FOR_DELIVERY → ARRIVED → DELIVERED
 *
 * Driver assignment is NOT a status — it is stored as {@code driverId} /
 * {@code driverAssignedAt} metadata on the order entity.  A driver can be
 * dispatched before or after the store marks READY_FOR_PICKUP; the two tracks
 * are independent.  The driver's pickup call requires both conditions to be met
 * (order is READY_FOR_PICKUP AND a driver is assigned) before transitioning to
 * OUT_FOR_DELIVERY.
 *
 * ── Scheduled flow ───────────────────────────────────────────────────────────
 *   SCHEDULED → (enters normal flow at PENDING, 30 min before delivery window)
 *
 * ── Terminal states ──────────────────────────────────────────────────────────
 *   DELIVERED  — order successfully completed.
 *   CANCELLED  — order cancelled by user, store, or system.
 *
 * ── Customer-facing timeline mapping (Cache 6) ───────────────────────────────
 *   PENDING                           → PLACED
 *   READY_FOR_PICKUP                  → PACKED
 *   OUT_FOR_DELIVERY                  → ON_THE_WAY
 *   ARRIVED                           → ARRIVED
 *   DELIVERED                         → DELIVERED
 */
public enum OrderStatus {

    /** Order placed by the customer; awaiting store acceptance. */
    PENDING,

    /** Store has accepted the order and is preparing it. */
    CONFIRMED,

    /** Order is packed and waiting for a courier to collect. */
    READY_FOR_PICKUP,

    /** Courier has collected the order and is delivering it to the customer. */
    OUT_FOR_DELIVERY,

    /** Courier has arrived at the customer's delivery address. */
    ARRIVED,

    /** Order was successfully delivered. Terminal state. */
    DELIVERED,

    /** Order was cancelled by the user, store, or system. Terminal state. */
    CANCELLED,

    /**
     * Order is scheduled for a future delivery window.
     * Transitions to PENDING 30 minutes before the requested delivery time,
     * at which point it enters the store's active queue.
     */
    SCHEDULED,

    /**
     * Store has proposed alternative item quantities and is waiting for the
     * customer to respond.  The customer can accept the proposed amounts,
     * remove specific items and continue, or cancel the order entirely.
     * If the customer does not respond in time the order is cancelled with
     * reason {@link OrderCancellationReason#AWAITING_CUSTOMER_RESPONSE_TIMEOUT}.
     */
    AWAITING_CUSTOMER_RESPONSE
}
