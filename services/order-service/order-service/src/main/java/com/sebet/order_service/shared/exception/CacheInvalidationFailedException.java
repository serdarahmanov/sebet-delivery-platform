package com.sebet.order_service.shared.exception;

public class CacheInvalidationFailedException extends RuntimeException {

    public CacheInvalidationFailedException(String orderId, Throwable cause) {
        super("Order write committed but cache invalidation failed and fallback eviction event could not be recorded for order "
                + orderId + "; retry the same request with the same Idempotency-Key", cause);
    }
}
