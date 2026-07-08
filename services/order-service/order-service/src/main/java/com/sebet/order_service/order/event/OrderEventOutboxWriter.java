package com.sebet.order_service.order.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OutboxEventEntity;
import com.sebet.order_service.persistence.repository.OutboxEventRepository;
import com.sebet.order_service.shared.enums.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OrderEventOutboxWriter {

    private static final String AGGREGATE_TYPE = "Order";
    private static final String SOURCE = "order-service";
    private static final String ORDER_CREATED = "OrderCreated";
    private static final String ORDER_SCHEDULED = "OrderScheduled";
    private static final String ORDER_ACTIVATED = "OrderActivated";
    private static final String ORDER_ACCEPTED = "OrderAccepted";
    private static final String ORDER_CANCELLED = "OrderCancelled";
    private static final String ORDER_READY_FOR_PICKUP = "OrderReadyForPickup";
    private static final String ORDER_PICKED_UP = "OrderPickedUp";
    private static final String ORDER_ARRIVED = "OrderArrived";
    private static final String ORDER_DELIVERED = "OrderDelivered";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void saveOrderCreated(OrderEntity order) {
        OffsetDateTime occurredAt = order.getCreatedAt() != null ? order.getCreatedAt() : OffsetDateTime.now();
        OrderCreatedEventData data = new OrderCreatedEventData(
                order.getId().toString(),
                order.getCustomerId(),
                order.getStoreId(),
                order.getCartId(),
                order.getStatus(),
                order.getScheduleType(),
                iso(order.getScheduledFor()),
                order.getSubtotalAmount(),
                order.getItemDiscountAmount(),
                order.getOrderDiscountAmount(),
                order.getDeliveryFeeAmount(),
                order.getTotalAmount(),
                order.getCurrency(),
                iso(order.getCreatedAt())
        );
        saveEvent(order, orderCreatedEventType(order), occurredAt, data);
    }

    public void saveOrderStatusTransition(
            OrderEntity order,
            OrderStatus previousStatus,
            OrderStatus newStatus,
            OffsetDateTime changedAt,
            String changedByType,
            String changedById,
            String reason,
            String metadataJson
    ) {
        OrderStatusTransitionEventData data = new OrderStatusTransitionEventData(
                order.getId().toString(),
                order.getCustomerId(),
                order.getStoreId(),
                order.getDriverId(),
                previousStatus,
                newStatus,
                changedByType,
                changedById,
                reason,
                metadataJson,
                order.getCancelledBy(),
                order.getCancellationReason(),
                iso(changedAt),
                iso(order.getDeliveredAt()),
                iso(order.getCancelledAt())
        );
        saveEvent(order, statusTransitionEventType(previousStatus, newStatus), changedAt, data);
    }

    private String orderCreatedEventType(OrderEntity order) {
        if (order.getStatus() == OrderStatus.SCHEDULED) {
            return ORDER_SCHEDULED;
        }
        return ORDER_CREATED;
    }

    private String statusTransitionEventType(OrderStatus previousStatus, OrderStatus newStatus) {
        if (newStatus == OrderStatus.CANCELLED) {
            return ORDER_CANCELLED;
        }
        if (previousStatus == OrderStatus.SCHEDULED && newStatus == OrderStatus.PENDING) {
            return ORDER_ACTIVATED;
        }
        if (previousStatus == OrderStatus.PENDING && newStatus == OrderStatus.CONFIRMED) {
            return ORDER_ACCEPTED;
        }
        if (previousStatus == OrderStatus.CONFIRMED && newStatus == OrderStatus.READY_FOR_PICKUP) {
            return ORDER_READY_FOR_PICKUP;
        }
        if (previousStatus == OrderStatus.READY_FOR_PICKUP && newStatus == OrderStatus.OUT_FOR_DELIVERY) {
            return ORDER_PICKED_UP;
        }
        if (previousStatus == OrderStatus.OUT_FOR_DELIVERY && newStatus == OrderStatus.ARRIVED) {
            return ORDER_ARRIVED;
        }
        if (previousStatus == OrderStatus.ARRIVED && newStatus == OrderStatus.DELIVERED) {
            return ORDER_DELIVERED;
        }
        throw new IllegalArgumentException(
                "Unsupported order event transition: " + previousStatus + " -> " + newStatus
        );
    }

    private void saveEvent(OrderEntity order, String eventType, OffsetDateTime occurredAt, Object data) {
        UUID eventId = UUID.randomUUID();
        OrderEventEnvelope<Object> envelope = new OrderEventEnvelope<>(
                eventId.toString(),
                eventType,
                order.getId().toString(),
                AGGREGATE_TYPE,
                eventVersion(order),
                iso(occurredAt),
                SOURCE,
                data
        );

        outboxEventRepository.save(OutboxEventEntity.builder()
                .id(eventId)
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(order.getId().toString())
                .eventType(eventType)
                .eventKey(order.getId().toString())
                .payload(toJson(envelope))
                .occurredAt(occurredAt)
                .build());
    }

    private long eventVersion(OrderEntity order) {
        return order.getVersion() == null ? 1L : order.getVersion() + 1L;
    }

    private String toJson(OrderEventEnvelope<Object> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize order outbox event", exception);
        }
    }

    private String iso(OffsetDateTime value) {
        return value == null ? null : value.toString();
    }
}
