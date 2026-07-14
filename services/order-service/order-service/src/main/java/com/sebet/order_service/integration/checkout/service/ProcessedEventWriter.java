package com.sebet.order_service.integration.checkout.service;

import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedPayload;
import com.sebet.order_service.integration.checkout.event.IntegrationEvent;
import com.sebet.order_service.integration.checkout.consumer.ProcessedEventInProgressException;
import com.sebet.order_service.persistence.entity.ProcessedEventEntity;
import com.sebet.order_service.persistence.repository.ProcessedEventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class ProcessedEventWriter {

    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_COMPLETED = "COMPLETED";

    private final ProcessedEventRepository processedEventRepository;
    private final EntityManager entityManager;
    private final TransactionTemplate requiresNewTransactionTemplate;
    private final Duration inProgressLease;

    public ProcessedEventWriter(
            ProcessedEventRepository processedEventRepository,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager,
            @Value("${order-service.kafka.checkout-events.processed-events.in-progress-lease}")
            Duration inProgressLease
    ) {
        this.processedEventRepository = processedEventRepository;
        this.entityManager = entityManager;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehaviorName("PROPAGATION_REQUIRES_NEW");
        this.inProgressLease = inProgressLease;
    }

    @Deprecated
    public boolean isAlreadyProcessed(UUID eventId) {
        return processedEventRepository.findById(eventId)
                .filter(event -> STATUS_COMPLETED.equals(event.getStatus()))
                .isPresent();
    }

    public ProcessedEventReservation reserve(IntegrationEvent<CheckoutConfirmedPayload> event, String ownerToken) {
        try {
            return requiresNewTransactionTemplate.execute(status -> {
                OffsetDateTime now = OffsetDateTime.now();
                ProcessedEventEntity entity = ProcessedEventEntity.builder()
                        .eventId(event.eventId())
                        .eventType(event.eventType())
                        .occurredAt(event.occurredAt() == null
                                ? null
                                : OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
                        .status(STATUS_IN_PROGRESS)
                        .lockedBy(ownerToken)
                        .lockedUntil(now.plus(inProgressLease))
                        .build();
                entityManager.persist(entity);
                entityManager.flush();
                return ProcessedEventReservation.acquired(ownerToken);
            });
        } catch (DataIntegrityViolationException | PersistenceException duplicateEvent) {
            return resolveExistingReservation(event.eventId(), ownerToken);
        }
    }

    public void markCompleted(UUID eventId, String ownerToken) {
        requiresNewTransactionTemplate.executeWithoutResult(status -> {
            int updated = processedEventRepository.markCompleted(eventId, ownerToken, OffsetDateTime.now());
            if (updated != 1) {
                throw new ProcessedEventInProgressException(eventId);
            }
        });
    }

    public void releaseInProgress(UUID eventId, String ownerToken) {
        requiresNewTransactionTemplate.executeWithoutResult(status ->
                processedEventRepository.deleteByEventIdAndStatusAndLockedBy(
                        eventId,
                        STATUS_IN_PROGRESS,
                        ownerToken
                )
        );
    }

    @Transactional
    @Deprecated
    public void markProcessed(IntegrationEvent<CheckoutConfirmedPayload> event) {
        processedEventRepository.save(ProcessedEventEntity.builder()
                .eventId(event.eventId())
                .eventType(event.eventType())
                .occurredAt(event.occurredAt() == null
                        ? null
                        : OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
                .status(STATUS_COMPLETED)
                .completedAt(OffsetDateTime.now())
                .build());
    }

    private ProcessedEventReservation resolveExistingReservation(UUID eventId, String ownerToken) {
        return requiresNewTransactionTemplate.execute(status -> {
            ProcessedEventEntity existing = processedEventRepository.findById(eventId)
                    .orElseThrow(() -> new IllegalStateException("Processed event reservation disappeared"));

            if (STATUS_COMPLETED.equals(existing.getStatus())) {
                return ProcessedEventReservation.alreadyCompleted();
            }
            if (STATUS_IN_PROGRESS.equals(existing.getStatus()) && isExpired(existing)) {
                OffsetDateTime now = OffsetDateTime.now();
                int reclaimed = processedEventRepository.reclaimExpiredInProgress(
                        eventId,
                        ownerToken,
                        now.plus(inProgressLease),
                        now
                );
                if (reclaimed == 1) {
                    return ProcessedEventReservation.acquired(ownerToken);
                }
            }

            throw new ProcessedEventInProgressException(eventId);
        });
    }

    private boolean isExpired(ProcessedEventEntity existing) {
        return existing.getLockedUntil() != null && existing.getLockedUntil().isBefore(OffsetDateTime.now());
    }

    public record ProcessedEventReservation(boolean shouldProcess, String ownerToken) {

        static ProcessedEventReservation acquired(String ownerToken) {
            return new ProcessedEventReservation(true, ownerToken);
        }

        static ProcessedEventReservation alreadyCompleted() {
            return new ProcessedEventReservation(false, null);
        }
    }
}
