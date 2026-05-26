package com.sebet.cartservice.cart.store.projection.event;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor

public class StoreEventListener {

    private final StoreEventHandler handler;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${app.kafka.topics.store-events}",
            groupId = "${spring.kafka.consumer.group-id}"

    )
    public void listen(String message) {
        try {
            StoreEvent event = objectMapper.readValue(message, StoreEvent.class);

            log.info(
                    "Received store event. eventId={}, eventType={}, aggregateId={}",
                    event.eventId(),
                    event.eventType(),
                    event.aggregateId()
            );
            handler.handle(event);

        } catch (JacksonException ex) {
            log.error("Unparseable store event, routing to DLT. message={}", message, ex);
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to process store event, will retry. message={}", message, ex);
            throw new RuntimeException("Failed to process store event", ex);
        }




    }

}
