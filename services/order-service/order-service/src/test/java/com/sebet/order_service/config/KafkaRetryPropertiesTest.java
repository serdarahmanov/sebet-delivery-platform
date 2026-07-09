package com.sebet.order_service.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaRetryPropertiesTest {

    @Test
    void bindsCheckoutKafkaRetryAndDltProperties() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("order-service.kafka.checkout-events.topic", "checkout-events-custom")
                .withProperty("order-service.kafka.checkout-events.dlt-topic", "checkout-events-custom.DLT")
                .withProperty("order-service.kafka.checkout-events.validate-topics", "true")
                .withProperty("order-service.kafka.checkout-events.retry.initial-interval-ms", "250")
                .withProperty("order-service.kafka.checkout-events.retry.multiplier", "1.5")
                .withProperty("order-service.kafka.checkout-events.retry.max-interval-ms", "5000")
                .withProperty("order-service.kafka.checkout-events.retry.max-attempts", "7");

        KafkaRetryProperties properties = Binder.get(environment)
                .bind("order-service.kafka.checkout-events", KafkaRetryProperties.class)
                .orElseThrow(() -> new AssertionError("Kafka retry properties did not bind"));

        assertThat(properties.getTopic()).isEqualTo("checkout-events-custom");
        assertThat(properties.getDltTopic()).isEqualTo("checkout-events-custom.DLT");
        assertThat(properties.isValidateTopics()).isTrue();
        assertThat(properties.getRetry().getInitialIntervalMs()).isEqualTo(250);
        assertThat(properties.getRetry().getMultiplier()).isEqualTo(1.5);
        assertThat(properties.getRetry().getMaxIntervalMs()).isEqualTo(5000);
        assertThat(properties.getRetry().getMaxAttempts()).isEqualTo(7);
    }

    @Test
    void bindsOrderEventsKafkaProperties() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("order-service.kafka.order-events.topic", "order-events-custom")
                .withProperty("order-service.kafka.order-events.group-id", "projection-group")
                .withProperty("order-service.kafka.order-events.auto-startup", "true")
                .withProperty("order-service.kafka.order-events.dlt-topic", "order-events-custom.DLT")
                .withProperty("order-service.kafka.order-events.validate-topics", "true");

        OrderEventsKafkaProperties properties = Binder.get(environment)
                .bind("order-service.kafka.order-events", OrderEventsKafkaProperties.class)
                .orElseThrow(() -> new AssertionError("Order event Kafka properties did not bind"));

        assertThat(properties.getTopic()).isEqualTo("order-events-custom");
        assertThat(properties.getGroupId()).isEqualTo("projection-group");
        assertThat(properties.isAutoStartup()).isTrue();
        assertThat(properties.getDltTopic()).isEqualTo("order-events-custom.DLT");
        assertThat(properties.isValidateTopics()).isTrue();
    }
}
