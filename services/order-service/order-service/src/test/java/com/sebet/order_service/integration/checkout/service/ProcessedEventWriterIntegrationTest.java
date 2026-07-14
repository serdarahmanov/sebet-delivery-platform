package com.sebet.order_service.integration.checkout.service;

import com.sebet.order_service.integration.checkout.CheckoutEventTestFactory;
import com.sebet.order_service.integration.checkout.consumer.ProcessedEventInProgressException;
import com.sebet.order_service.integration.checkout.event.CheckoutConfirmedPayload;
import com.sebet.order_service.integration.checkout.event.IntegrationEvent;
import com.sebet.order_service.persistence.entity.ProcessedEventEntity;
import com.sebet.order_service.persistence.repository.ProcessedEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ProcessedEventWriter.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProcessedEventWriterIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine")
    );

    @Autowired
    private ProcessedEventWriter writer;

    @Autowired
    private ProcessedEventRepository repository;

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.show-sql", () -> "false");
        registry.add("order-service.kafka.checkout-events.processed-events.in-progress-lease", () -> "PT30S");
    }

    @AfterEach
    void cleanup() {
        repository.deleteAll();
    }

    @Test
    void reserveStoresInProgressThenMarkCompletedMakesFutureReserveNoOp() {
        IntegrationEvent<CheckoutConfirmedPayload> event = CheckoutEventTestFactory.checkoutEvent("cart-reserve");

        ProcessedEventWriter.ProcessedEventReservation reservation = writer.reserve(event, "instance-a");

        assertThat(reservation.shouldProcess()).isTrue();
        ProcessedEventEntity inProgress = repository.findById(event.eventId()).orElseThrow();
        assertThat(inProgress.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(inProgress.getLockedBy()).isEqualTo("instance-a");
        assertThat(inProgress.getLockedUntil()).isAfter(OffsetDateTime.now());

        assertThatThrownBy(() -> writer.reserve(event, "instance-b"))
                .isInstanceOf(ProcessedEventInProgressException.class);

        writer.markCompleted(event.eventId(), reservation.ownerToken());

        ProcessedEventEntity completed = repository.findById(event.eventId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(completed.getLockedBy()).isNull();
        assertThat(completed.getLockedUntil()).isNull();
        assertThat(completed.getCompletedAt()).isNotNull();

        ProcessedEventWriter.ProcessedEventReservation duplicate = writer.reserve(event, "instance-c");
        assertThat(duplicate.shouldProcess()).isFalse();
    }

    @Test
    void expiredInProgressEventCanBeReclaimed() {
        IntegrationEvent<CheckoutConfirmedPayload> event = CheckoutEventTestFactory.checkoutEvent("cart-expired");
        repository.saveAndFlush(ProcessedEventEntity.builder()
                .eventId(event.eventId())
                .eventType(event.eventType())
                .occurredAt(OffsetDateTime.now().minusMinutes(10))
                .status("IN_PROGRESS")
                .lockedBy("dead-instance")
                .lockedUntil(OffsetDateTime.now().minusMinutes(5))
                .build());

        ProcessedEventWriter.ProcessedEventReservation reservation = writer.reserve(event, "instance-b");

        assertThat(reservation.shouldProcess()).isTrue();
        ProcessedEventEntity reclaimed = repository.findById(event.eventId()).orElseThrow();
        assertThat(reclaimed.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(reclaimed.getLockedBy()).isEqualTo("instance-b");
        assertThat(reclaimed.getLockedUntil()).isAfter(OffsetDateTime.now());
    }

    @Test
    void releaseDeletesOnlyOwnedInProgressReservation() {
        IntegrationEvent<CheckoutConfirmedPayload> event = CheckoutEventTestFactory.checkoutEvent("cart-release");
        writer.reserve(event, "instance-a");

        writer.releaseInProgress(event.eventId(), "instance-b");
        assertThat(repository.findById(event.eventId())).isPresent();

        writer.releaseInProgress(event.eventId(), "instance-a");
        assertThat(repository.findById(event.eventId())).isEmpty();
    }

    @Test
    void markCompletedRequiresOwningReservation() {
        IntegrationEvent<CheckoutConfirmedPayload> event = CheckoutEventTestFactory.checkoutEvent("cart-wrong-owner");
        writer.reserve(event, "instance-a");

        assertThatThrownBy(() -> writer.markCompleted(event.eventId(), "instance-b"))
                .isInstanceOf(ProcessedEventInProgressException.class);

        ProcessedEventEntity inProgress = repository.findById(event.eventId()).orElseThrow();
        assertThat(inProgress.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(inProgress.getLockedBy()).isEqualTo("instance-a");
    }
}
