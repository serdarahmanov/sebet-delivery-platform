package com.sebet.order_service.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "order-service.kafka.order-events")
public class OrderEventsKafkaProperties {

    private String topic = "order-events";
    private String groupId = "order-service-driver-assignment-projection";
    private boolean autoStartup = false;
    private String dltTopic = "order-events.DLT";
    private boolean validateTopics = false;
}
