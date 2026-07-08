package com.sebet.order_service.shared.enums;

/**
 * Whether the order is to be delivered as soon as possible or at a scheduled future time.
 * Mirrored from cart-service's {@code ScheduleType}; keep in sync.
 */
public enum ScheduleType {
    ASAP,
    SCHEDULED
}
