package com.sebet.order_service.shared.exception;

public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String action) {
        super("Idempotency-Key was already used for a different " + action + " request");
    }
}
