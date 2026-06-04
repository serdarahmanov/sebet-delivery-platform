package com.sebet.order_service.shared.enums;

/**
 * Who initiated the cancellation of an order.
 */
public enum OrderCancelledBy {
    /** The customer cancelled through the app. */
    USER,
    /** The store rejected or cancelled the order. */
    STORE,
    /** The platform cancelled automatically (timeout, payment failure, system error). */
    SYSTEM
}
