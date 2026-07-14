package com.sebet.order_service.integration.checkout.consumer;

import java.util.UUID;

public class ProcessedEventInProgressException extends RuntimeException {

    public ProcessedEventInProgressException(UUID eventId) {
        super("Checkout event is already being processed: " + eventId);
    }
}
