package com.sebet.order_service.integration.checkout.consumer;

import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CheckoutConfirmedEventConsumer {

    private final CheckoutConfirmedEventProcessor checkoutConfirmedEventProcessor;

    @KafkaListener(
            topics = "${order-service.kafka.checkout-events.topic}",
            groupId = "${order-service.kafka.checkout-events.group-id}",
            autoStartup = "${order-service.kafka.checkout-events.auto-startup:false}"
    )
    public void consume(CheckoutConfirmedEvent event) {
        checkoutConfirmedEventProcessor.process(event);
    }
}
