package com.sebet.order_service.integration.checkout.service;

import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedPayload;
import com.sebet.order_service.integration.checkout.event.IntegrationEvent;
import com.sebet.order_service.persistence.entity.ProcessedEventEntity;
import com.sebet.order_service.persistence.repository.ProcessedEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class ProcessedEventWriter {

    private final ProcessedEventRepository processedEventRepository;

    public ProcessedEventWriter(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    public boolean isAlreadyProcessed(UUID eventId) {
        return processedEventRepository.existsById(eventId);
    }

    @Transactional
    public void markProcessed(IntegrationEvent<CheckoutConfirmedPayload> event) {
        processedEventRepository.save(ProcessedEventEntity.builder()
                .eventId(event.eventId())
                .eventType(event.eventType())
                .occurredAt(event.occurredAt() == null
                        ? null
                        : OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
                .build());
    }
}
