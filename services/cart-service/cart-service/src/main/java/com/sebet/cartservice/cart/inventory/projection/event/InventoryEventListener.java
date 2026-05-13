package com.sebet.cartservice.cart.inventory.projection.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryEventListener {

    private final ObjectMapper objectMapper;
    private final InventoryEventHandler eventHandler;



    @KafkaListener(
            topics = "${app.kafka.topics.inventory-events}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void listen(String message) {
        try {
            InventoryEvent event = objectMapper.readValue(message, InventoryEvent.class);

            log.info(
                    "Received inventory event. eventId={}, eventType={}, aggregateId={}",
                    event.eventId(),
                    event.eventType(),
                    event.aggregateId()
            );

            eventHandler.handle(event);
        } catch (Exception ex) {
            log.error("Failed to process inventory event message={}", message, ex);
            throw new RuntimeException("Failed to process inventory event", ex);
        }
    }
}
