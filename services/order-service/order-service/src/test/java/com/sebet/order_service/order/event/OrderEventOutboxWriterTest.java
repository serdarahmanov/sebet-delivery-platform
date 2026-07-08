package com.sebet.order_service.order.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OutboxEventEntity;
import com.sebet.order_service.persistence.repository.OutboxEventRepository;
import com.sebet.order_service.shared.enums.OrderCancellationReason;
import com.sebet.order_service.shared.enums.OrderCancelledBy;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ScheduleType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OrderEventOutboxWriterTest {

    private final OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OrderEventOutboxWriter writer = new OrderEventOutboxWriter(outboxEventRepository, objectMapper);

    @Test
    void saveOrderCreatedEmitsCreatedForImmediateOrder() throws Exception {
        OrderEntity order = order(OrderStatus.PENDING);

        writer.saveOrderCreated(order);

        assertSavedEvent("OrderCreated", order);
    }

    @Test
    void saveOrderCreatedEmitsScheduledForScheduledOrder() throws Exception {
        OrderEntity order = order(OrderStatus.SCHEDULED);

        writer.saveOrderCreated(order);

        assertSavedEvent("OrderScheduled", order);
    }

    @Test
    void saveOrderStatusTransitionEmitsSpecializedEventTypes() throws Exception {
        List<TransitionCase> cases = List.of(
                new TransitionCase(OrderStatus.SCHEDULED, OrderStatus.PENDING, "OrderActivated"),
                new TransitionCase(OrderStatus.PENDING, OrderStatus.CONFIRMED, "OrderAccepted"),
                new TransitionCase(OrderStatus.PENDING, OrderStatus.CANCELLED, "OrderCancelled"),
                new TransitionCase(OrderStatus.CONFIRMED, OrderStatus.READY_FOR_PICKUP, "OrderReadyForPickup"),
                new TransitionCase(OrderStatus.READY_FOR_PICKUP, OrderStatus.OUT_FOR_DELIVERY, "OrderPickedUp"),
                new TransitionCase(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.ARRIVED, "OrderArrived"),
                new TransitionCase(OrderStatus.ARRIVED, OrderStatus.DELIVERED, "OrderDelivered")
        );

        for (TransitionCase transition : cases) {
            OutboxEventRepository repository = mock(OutboxEventRepository.class);
            OrderEventOutboxWriter transitionWriter = new OrderEventOutboxWriter(repository, objectMapper);
            OrderEntity order = order(transition.newStatus());
            if (transition.newStatus() == OrderStatus.CANCELLED) {
                order.setCancelledBy(OrderCancelledBy.STORE);
                order.setCancellationReason(OrderCancellationReason.OUT_OF_STOCK);
                order.setCancelledAt(OffsetDateTime.parse("2026-07-08T10:05:00Z"));
            }

            transitionWriter.saveOrderStatusTransition(
                    order,
                    transition.previousStatus(),
                    transition.newStatus(),
                    OffsetDateTime.parse("2026-07-08T10:05:00Z"),
                    "STORE",
                    "store-1",
                    "TEST_REASON",
                    "{\"test\":true}"
            );

            ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
            verify(repository).save(captor.capture());
            OutboxEventEntity event = captor.getValue();
            JsonNode payload = objectMapper.readTree(event.getPayload());

            assertThat(event.getEventType()).isEqualTo(transition.eventType());
            assertThat(payload.path("eventType").asText()).isEqualTo(transition.eventType());
            assertThat(payload.path("data").path("previousStatus").asText())
                    .isEqualTo(transition.previousStatus().name());
            assertThat(payload.path("data").path("newStatus").asText())
                    .isEqualTo(transition.newStatus().name());
            if (transition.newStatus() == OrderStatus.CANCELLED) {
                assertThat(payload.path("data").path("cancelledBy").asText()).isEqualTo("STORE");
                assertThat(payload.path("data").path("cancellationReason").asText()).isEqualTo("OUT_OF_STOCK");
            }
        }
    }

    private void assertSavedEvent(String eventType, OrderEntity order) throws Exception {
        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxEventRepository).save(captor.capture());
        OutboxEventEntity event = captor.getValue();
        JsonNode payload = objectMapper.readTree(event.getPayload());

        assertThat(event.getEventType()).isEqualTo(eventType);
        assertThat(payload.path("eventType").asText()).isEqualTo(eventType);
        assertThat(payload.path("aggregateId").asText()).isEqualTo(order.getId().toString());
        assertThat(payload.path("data").path("status").asText()).isEqualTo(order.getStatus().name());
    }

    private OrderEntity order(OrderStatus status) {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-08T10:00:00Z");
        return OrderEntity.builder()
                .id(UUID.randomUUID())
                .version(0L)
                .customerId("customer-1")
                .storeId("store-1")
                .cartId("cart-1")
                .status(status)
                .scheduleType(status == OrderStatus.SCHEDULED ? ScheduleType.SCHEDULED : ScheduleType.IMMEDIATE)
                .scheduledFor(status == OrderStatus.SCHEDULED ? now.plusHours(2) : null)
                .subtotalAmount(new BigDecimal("33000.00"))
                .itemDiscountAmount(BigDecimal.ZERO)
                .orderDiscountAmount(BigDecimal.ZERO)
                .deliveryFeeAmount(new BigDecimal("3000.00"))
                .totalAmount(new BigDecimal("36000.00"))
                .currency("UZS")
                .deliveryAddressJson("{\"street\":\"Amir Temur 25\"}")
                .deliveryLat(new BigDecimal("41.311100"))
                .deliveryLng(new BigDecimal("69.279700"))
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private record TransitionCase(
            OrderStatus previousStatus,
            OrderStatus newStatus,
            String eventType
    ) {
    }
}
