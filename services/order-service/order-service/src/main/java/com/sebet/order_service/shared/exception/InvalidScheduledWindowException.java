package com.sebet.order_service.shared.exception;

public class InvalidScheduledWindowException extends RuntimeException {

    public InvalidScheduledWindowException(String reason) {
        super(reason);
    }
}
