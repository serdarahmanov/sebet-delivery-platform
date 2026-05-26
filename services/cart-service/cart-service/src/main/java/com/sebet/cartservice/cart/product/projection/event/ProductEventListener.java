package com.sebet.cartservice.cart.product.projection.event;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;


@Slf4j
@Component
@RequiredArgsConstructor
public class ProductEventListener {


    private final ObjectMapper objectMapper;
    private final ProductEventHandler eventHandler;

    @KafkaListener(
            topics = "${app.kafka.topics.product-events}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void listen(String message) {
        try{
            ProductEvent event = objectMapper.readValue(message, ProductEvent.class);

            log.info(
                    "Received product event. eventId={}, eventType={}, aggregateId={}",
                    event.eventId(),
                    event.eventType(),
                    event.aggregateId()
            );

            eventHandler.handle(event);

        } catch (JacksonException ex) {
            log.error("Unparseable product event, routing to DLT. message={}", message, ex);
            throw ex;
        } catch (Exception ex) {
            log.error("Failed to process product event, will retry. message={}", message, ex);
            throw new RuntimeException("Failed to process product event", ex);
        }

    }
}
