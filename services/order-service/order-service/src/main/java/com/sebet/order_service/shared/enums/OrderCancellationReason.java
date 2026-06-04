package com.sebet.order_service.shared.enums;

/**
 * Machine-readable reason code for an order cancellation.
 *
 * Store-initiated  : {@code STORE_REJECTED}, {@code OUT_OF_STOCK}, {@code STORE_CLOSED}
 * User-initiated   : {@code USER_REQUESTED}
 * System-initiated : {@code PAYMENT_FAILED}, {@code NO_RIDERS_AVAILABLE},
 *                    {@code STORE_RESPONSE_TIMEOUT}, {@code SYSTEM_ERROR}
 */
public enum OrderCancellationReason {
    /** Customer cancelled the order through the app. */
    USER_REQUESTED,

    /** Store deliberately rejected the order (too busy, closing early, etc.). */
    STORE_REJECTED,

    /** Store rejected because one or more items are unavailable. */
    OUT_OF_STOCK,

    /**
     * Store went offline or closed unexpectedly after the order was already accepted.
     * Distinct from {@code STORE_REJECTED} which is a deliberate choice made at acceptance time.
     */
    STORE_CLOSED,

    /**
     * Store accepted the order but cannot complete preparation for operational reasons
     * unrelated to stock (e.g. equipment failure, unexpected staff shortage).
     * Only valid for post-acceptance cancellations via POST /store/orders/{orderId}/cancel.
     */
    STORE_UNABLE_TO_FULFIL,

    /**
     * Delivery service could not assign a rider after the order was accepted and prepared.
     * Maps to {@code NO_RIDERS_AVAILABLE} in the cart-service's {@code StoreBasketIssueReason}.
     */
    NO_RIDERS_AVAILABLE,

    /** Payment could not be collected or was declined. */
    PAYMENT_FAILED,

    /** Store did not accept or reject the order within the allowed response window. */
    STORE_RESPONSE_TIMEOUT,

    /**
     * Store proposed alternative quantities but the customer did not respond
     * within the allowed window.
     */
    AWAITING_CUSTOMER_RESPONSE_TIMEOUT,

    /** Platform-level error; cancellation was triggered automatically. */
    SYSTEM_ERROR
}
