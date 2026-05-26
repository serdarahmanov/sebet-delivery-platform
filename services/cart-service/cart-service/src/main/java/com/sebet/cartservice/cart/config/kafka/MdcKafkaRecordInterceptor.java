package com.sebet.cartservice.cart.config.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class MdcKafkaRecordInterceptor implements RecordInterceptor<Object, Object> {

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        MDC.put("requestId", UUID.randomUUID().toString());
        MDC.put("kafkaTopic", record.topic());
        MDC.put("kafkaPartition", String.valueOf(record.partition()));
        MDC.put("kafkaOffset", String.valueOf(record.offset()));
        if (record.key() != null) {
            MDC.put("kafkaKey", record.key().toString());
        }
        return record;
    }

    @Override
    public void success(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        MDC.clear();
    }

    @Override
    public void failure(ConsumerRecord<Object, Object> record, Exception exception, Consumer<Object, Object> consumer) {
        MDC.clear();
    }
}
