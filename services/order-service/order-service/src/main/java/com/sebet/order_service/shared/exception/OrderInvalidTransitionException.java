package com.sebet.order_service.shared.exception;

import com.sebet.order_service.shared.enums.OrderStatus;

public class OrderInvalidTransitionException extends RuntimeException {

    public OrderInvalidTransitionException(String orderId, OrderStatus currentStatus, OrderStatus targetStatus) {
        super("Order " + orderId + " cannot transition from " + currentStatus + " to " + targetStatus);
    }
}
