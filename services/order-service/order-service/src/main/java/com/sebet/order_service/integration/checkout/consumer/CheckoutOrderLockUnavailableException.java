package com.sebet.order_service.integration.checkout.consumer;

public class CheckoutOrderLockUnavailableException extends RuntimeException {

    public CheckoutOrderLockUnavailableException(String cartId) {
        super("Checkout order lock is already held for cartId=" + cartId);
    }
}
