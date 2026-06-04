package com.sebet.order_service.shared.enums;

/**
 * Refund state for a cancelled order.
 */
public enum RefundStatus {
    /** Refund has been initiated but not yet settled. */
    REFUND_PENDING,
    /** Refund has been successfully completed. */
    REFUNDED
}
