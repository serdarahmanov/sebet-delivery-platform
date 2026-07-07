package com.sebet.order_service.shared.exception;

public class VerificationCodeNotFoundException extends RuntimeException {
    public VerificationCodeNotFoundException(String orderId) {
        super("Verification code not found for order: " + orderId);
    }
}
