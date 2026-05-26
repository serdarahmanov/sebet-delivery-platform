package com.sebet.cartservice.cart.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class KafkaMetrics {

    private final MeterRegistry registry;

    public KafkaMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Fired when a Kafka message has been successfully sent to the DLT topic
     * (i.e. after {@code super.publish()} returns without throwing — broker has confirmed
     * receipt). If the broker send itself throws, {@link #recordDltSendFailed} fires instead
     * and this counter is NOT incremented.
     *
     * @param topic  the original topic (e.g. "product-events", not the DLT topic)
     * @param reason "parse_error"      — message was unparseable (upstream schema problem)
     *               "processing_error" — handler threw after retries (infrastructure problem)
     */
    public void recordDltPublished(String topic, String reason) {
        registry.counter("kafka.dlt.published", "topic", topic, "reason", reason).increment();
    }

    /**
     * Fired when the broker send to the DLT topic itself fails.
     * This is a double failure: the message was not processed AND is not in the DLT.
     * It is unrecoverable — the projection update is permanently lost with no audit trail.
     *
     * @param topic the original topic (e.g. "product-events", not the DLT topic)
     */
    public void recordDltSendFailed(String topic) {
        registry.counter("kafka.dlt.send_failed", "topic", topic).increment();
    }
}
