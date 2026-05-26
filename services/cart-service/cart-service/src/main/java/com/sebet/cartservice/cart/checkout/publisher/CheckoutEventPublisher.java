package com.sebet.cartservice.cart.checkout.publisher;

import tools.jackson.databind.ObjectMapper;
import com.sebet.cartservice.cart.checkout.event.CheckoutConfirmedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
public class CheckoutEventPublisher {

    private static final int ACK_TIMEOUT_SECONDS = 5;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.kafka.topics.checkout-events}")
    private String checkoutEventsTopic;

    public void publish(CheckoutConfirmedEvent event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize CheckoutConfirmedEvent", e);
        }

        try {
            kafkaTemplate.send(checkoutEventsTopic, event.aggregateId(), payload)
                    .get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("CheckoutConfirmed event published, basketId={}, cartId={}",
                    event.data().basketId(), event.aggregateId());
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            log.error("Failed to publish CheckoutConfirmed event, basketId={}", event.data().basketId(), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Kafka publish failed", e);
        }
    }
}
