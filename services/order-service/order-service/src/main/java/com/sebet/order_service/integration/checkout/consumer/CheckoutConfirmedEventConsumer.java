package com.sebet.order_service.integration.checkout.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedPayload;
import com.sebet.order_service.integration.checkout.event.IntegrationEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CheckoutConfirmedEventConsumer {

    private static final TypeReference<IntegrationEvent<CheckoutConfirmedPayload>> CHECKOUT_CONFIRMED_TYPE =
            new TypeReference<>() {
            };

    private final ObjectMapper objectMapper;
    private final CheckoutConfirmedHandler checkoutConfirmedHandler;

    @KafkaListener(
            topics = "${order-service.kafka.checkout-events.topic}",
            groupId = "${order-service.kafka.checkout-events.group-id}",
            autoStartup = "${order-service.kafka.checkout-events.auto-startup:false}"
    )
    public void consume(String payload) {
        checkoutConfirmedHandler.handle(deserialize(payload));
    }

    private IntegrationEvent<CheckoutConfirmedPayload> deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, CHECKOUT_CONFIRMED_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to deserialize CheckoutConfirmed event", exception);
        }
    }
}
