package com.sebet.order_service.shared.exception;

public class ScheduledOrderModificationWindowClosedException extends RuntimeException {

    public ScheduledOrderModificationWindowClosedException(String orderId) {
        super("Order " + orderId + " is outside the modification window and can no longer be changed");
    }
}
