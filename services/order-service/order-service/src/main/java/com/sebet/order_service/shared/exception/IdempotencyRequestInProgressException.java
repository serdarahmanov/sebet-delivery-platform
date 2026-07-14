package com.sebet.order_service.shared.exception;

public class IdempotencyRequestInProgressException extends RuntimeException {

    public IdempotencyRequestInProgressException(String action) {
        super("A request with this Idempotency-Key is already in progress for action " + action);
    }
}
