package com.sebet.order_service.shared.exception;

public class DriverNotAssignedException extends RuntimeException {
    public DriverNotAssignedException(String orderId) {
        super("Driver is not assigned to order: " + orderId);
    }
}
