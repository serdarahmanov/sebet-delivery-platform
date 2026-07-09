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

    @Test
    void saveDriverAssignmentDeclinedEmitsAssignmentEvent() throws Exception {
        OrderEntity order = order(OrderStatus.READY_FOR_PICKUP);
        OffsetDateTime declinedAt = OffsetDateTime.parse("2026-07-08T10:15:00Z");

        writer.saveDriverAssignmentDeclined(order, "driver-1", declinedAt, "DRIVER_DECLINED");

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxEventRepository).save(captor.capture());
        OutboxEventEntity event = captor.getValue();
        JsonNode payload = objectMapper.readTree(event.getPayload());

        assertThat(event.getEventType()).isEqualTo("DriverAssignmentDeclined");
        assertThat(event.getAggregateType()).isEqualTo("Order");
        assertThat(event.getAggregateId()).isEqualTo(order.getId().toString());
        assertThat(event.getEventKey()).isEqualTo(order.getId().toString());
        assertThat(event.getOccurredAt()).isEqualTo(declinedAt);
        assertThat(payload.path("eventType").asText()).isEqualTo("DriverAssignmentDeclined");
        assertThat(payload.path("data").path("orderId").asText()).isEqualTo(order.getId().toString());
        assertThat(payload.path("data").path("customerId").asText()).isEqualTo("customer-1");
        assertThat(payload.path("data").path("storeId").asText()).isEqualTo("store-1");
        assertThat(payload.path("data").path("driverId").asText()).isEqualTo("driver-1");
        assertThat(payload.path("data").path("status").asText()).isEqualTo("READY_FOR_PICKUP");
        assertThat(payload.path("data").path("declinedAt").asText()).isEqualTo(declinedAt.toString());
        assertThat(payload.path("data").path("reason").asText()).isEqualTo("DRIVER_DECLINED");
    }

    @Test
    void saveDriverAssignedEmitsAssignmentEvent() throws Exception {
        OrderEntity order = order(OrderStatus.CONFIRMED);
        OffsetDateTime assignedAt = OffsetDateTime.parse("2026-07-08T10:15:00Z");

        writer.saveDriverAssigned(order, "driver-1", assignedAt);

        JsonNode payload = assertAssignmentEvent("DriverAssigned", order, assignedAt);
        assertThat(payload.path("data").path("driverId").asText()).isEqualTo("driver-1");
        assertThat(payload.path("data").path("assignedAt").asText()).isEqualTo(assignedAt.toString());
    }

    @Test
    void saveDriverReplacedEmitsReplacementEvent() throws Exception {
        OrderEntity order = order(OrderStatus.READY_FOR_PICKUP);
        OffsetDateTime replacedAt = OffsetDateTime.parse("2026-07-08T10:20:00Z");

        writer.saveDriverReplaced(order, "old-driver", "new-driver", replacedAt);

        JsonNode payload = assertAssignmentEvent("DriverReplaced", order, replacedAt);
        assertThat(payload.path("data").path("previousDriverId").asText()).isEqualTo("old-driver");
        assertThat(payload.path("data").path("newDriverId").asText()).isEqualTo("new-driver");
        assertThat(payload.path("data").path("replacedAt").asText()).isEqualTo(replacedAt.toString());
    }

    @Test
    void saveDriverUnassignedEmitsUnassignmentEvent() throws Exception {
        OrderEntity order = order(OrderStatus.OUT_FOR_DELIVERY);
        OffsetDateTime unassignedAt = OffsetDateTime.parse("2026-07-08T10:25:00Z");

        writer.saveDriverUnassigned(order, "driver-1", unassignedAt, "ADMIN_OVERRIDE");

        JsonNode payload = assertAssignmentEvent("DriverUnassigned", order, unassignedAt);
        assertThat(payload.path("data").path("previousDriverId").asText()).isEqualTo("driver-1");
        assertThat(payload.path("data").path("unassignedAt").asText()).isEqualTo(unassignedAt.toString());
        assertThat(payload.path("data").path("reason").asText()).isEqualTo("ADMIN_OVERRIDE");
    }

    @Test
    void saveOrderCacheEvictionRequestedEmitsCacheEvictionEvent() throws Exception {
        String orderId = UUID.randomUUID().toString();
        OffsetDateTime requestedAt = OffsetDateTime.parse("2026-07-08T10:30:00Z");

        writer.saveOrderCacheEvictionRequested(
                orderId,
                "C2",
                "order:" + orderId,
                "DRIVER_ASSIGNMENT_CHANGED",
                "INTERNAL_ASSIGN_DRIVER",
                "idem-1",
                "RedisConnectionFailureException",
                "redis unavailable",
                requestedAt
        );

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxEventRepository).save(captor.capture());
        OutboxEventEntity event = captor.getValue();
        JsonNode payload = objectMapper.readTree(event.getPayload());

        assertThat(event.getEventType()).isEqualTo("OrderCacheEvictionRequested");
        assertThat(event.getAggregateType()).isEqualTo("Order");
        assertThat(event.getAggregateId()).isEqualTo(orderId);
        assertThat(event.getEventKey()).isEqualTo(orderId);
        assertThat(event.getOccurredAt()).isEqualTo(requestedAt);
        assertThat(payload.path("eventType").asText()).isEqualTo("OrderCacheEvictionRequested");
        assertThat(payload.path("aggregateId").asText()).isEqualTo(orderId);
        assertThat(payload.path("data").path("orderId").asText()).isEqualTo(orderId);
        assertThat(payload.path("data").path("cacheName").asText()).isEqualTo("C2");
        assertThat(payload.path("data").path("cacheKey").asText()).isEqualTo("order:" + orderId);
        assertThat(payload.path("data").path("reason").asText()).isEqualTo("DRIVER_ASSIGNMENT_CHANGED");
        assertThat(payload.path("data").path("sourceAction").asText()).isEqualTo("INTERNAL_ASSIGN_DRIVER");
        assertThat(payload.path("data").path("idempotencyKey").asText()).isEqualTo("idem-1");
        assertThat(payload.path("data").path("failureType").asText()).isEqualTo("RedisConnectionFailureException");
        assertThat(payload.path("data").path("failureMessage").asText()).isEqualTo("redis unavailable");
        assertThat(payload.path("data").path("requestedAt").asText()).isEqualTo(requestedAt.toString());
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

    private JsonNode assertAssignmentEvent(String eventType, OrderEntity order, OffsetDateTime occurredAt) throws Exception {
        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxEventRepository).save(captor.capture());
        OutboxEventEntity event = captor.getValue();
        JsonNode payload = objectMapper.readTree(event.getPayload());

        assertThat(event.getEventType()).isEqualTo(eventType);
        assertThat(event.getAggregateType()).isEqualTo("Order");
        assertThat(event.getAggregateId()).isEqualTo(order.getId().toString());
        assertThat(event.getEventKey()).isEqualTo(order.getId().toString());
        assertThat(event.getOccurredAt()).isEqualTo(occurredAt);
        assertThat(payload.path("eventType").asText()).isEqualTo(eventType);
        assertThat(payload.path("data").path("orderId").asText()).isEqualTo(order.getId().toString());
        assertThat(payload.path("data").path("customerId").asText()).isEqualTo("customer-1");
        assertThat(payload.path("data").path("storeId").asText()).isEqualTo("store-1");
        assertThat(payload.path("data").path("status").asText()).isEqualTo(order.getStatus().name());
        return payload;
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
                .scheduleType(status == OrderStatus.SCHEDULED ? ScheduleType.SCHEDULED : ScheduleType.ASAP)
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
