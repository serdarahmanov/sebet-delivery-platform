package com.sebet.order_service.order.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OutboxEventEntity;
import com.sebet.order_service.persistence.repository.OutboxEventRepository;
import com.sebet.order_service.shared.enums.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
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
    private static final String ORDER_ACTIVE_PROPOSAL_CANCELLED = "OrderActiveProposalCancelled";
    private static final String DRIVER_ASSIGNED = "DriverAssigned";
    private static final String DRIVER_REPLACED = "DriverReplaced";
    private static final String DRIVER_UNASSIGNED = "DriverUnassigned";
    private static final String DRIVER_ASSIGNMENT_DECLINED = "DriverAssignmentDeclined";
    private static final String ORDER_CACHE_EVICTION_REQUESTED = "OrderCacheEvictionRequested";
    private static final String ORDER_PROPOSED_TO_CUSTOMER = "OrderProposedToCustomer";

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

    public void saveOrderProposedToCustomer(OrderEntity order, String itemsJson, OffsetDateTime proposedAt) {
        OrderProposedToCustomerEventData data = new OrderProposedToCustomerEventData(
                order.getId().toString(),
                order.getCustomerId(),
                order.getStoreId(),
                itemsJson,
                iso(proposedAt)
        );
        saveEvent(order, ORDER_PROPOSED_TO_CUSTOMER, proposedAt, data);
    }

    public void saveDriverAssignmentDeclined(
            OrderEntity order,
            String driverId,
            OffsetDateTime declinedAt,
            String reason
    ) {
        DriverAssignmentDeclinedEventData data = new DriverAssignmentDeclinedEventData(
                order.getId().toString(),
                order.getCustomerId(),
                order.getStoreId(),
                driverId,
                order.getStatus(),
                iso(declinedAt),
                reason
        );
        saveEvent(order, DRIVER_ASSIGNMENT_DECLINED, declinedAt, data);
    }

    public void saveDriverAssigned(OrderEntity order, String driverId, OffsetDateTime assignedAt) {
        DriverAssignedEventData data = new DriverAssignedEventData(
                order.getId().toString(),
                order.getCustomerId(),
                order.getStoreId(),
                driverId,
                order.getStatus(),
                iso(assignedAt)
        );
        saveEvent(order, DRIVER_ASSIGNED, assignedAt, data);
    }

    public void saveDriverReplaced(
            OrderEntity order,
            String previousDriverId,
            String newDriverId,
            OffsetDateTime replacedAt
    ) {
        DriverReplacedEventData data = new DriverReplacedEventData(
                order.getId().toString(),
                order.getCustomerId(),
                order.getStoreId(),
                previousDriverId,
                newDriverId,
                order.getStatus(),
                iso(replacedAt)
        );
        saveEvent(order, DRIVER_REPLACED, replacedAt, data);
    }

    public void saveDriverUnassigned(
            OrderEntity order,
            String previousDriverId,
            OffsetDateTime unassignedAt,
            String reason
    ) {
        DriverUnassignedEventData data = new DriverUnassignedEventData(
                order.getId().toString(),
                order.getCustomerId(),
                order.getStoreId(),
                previousDriverId,
                order.getStatus(),
                iso(unassignedAt),
                reason
        );
        saveEvent(order, DRIVER_UNASSIGNED, unassignedAt, data);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveOrderCacheEvictionRequested(
            String orderId,
            String cacheName,
            String cacheKey,
            String reason,
            String sourceAction,
            String idempotencyKey,
            String failureType,
            String failureMessage,
            OffsetDateTime requestedAt
    ) {
        saveOrderCacheEvictionRequested(
                orderId,
                cacheName,
                cacheKey,
                List.of(cacheKey),
                reason,
                sourceAction,
                idempotencyKey,
                failureType,
                failureMessage,
                requestedAt
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveOrderCacheEvictionRequested(
            String orderId,
            String cacheName,
            String cacheKey,
            List<String> cacheKeys,
            String reason,
            String sourceAction,
            String idempotencyKey,
            String failureType,
            String failureMessage,
            OffsetDateTime requestedAt
    ) {
        OrderCacheEvictionRequestedEventData data = new OrderCacheEvictionRequestedEventData(
                orderId,
                cacheName,
                cacheKey,
                cacheKeys,
                reason,
                sourceAction,
                idempotencyKey,
                failureType,
                failureMessage,
                iso(requestedAt)
        );
        saveEvent(orderId, ORDER_CACHE_EVICTION_REQUESTED, requestedAt, data);
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
        if (previousStatus == OrderStatus.AWAITING_CUSTOMER_RESPONSE && newStatus == OrderStatus.CONFIRMED) {
            return ORDER_ACTIVE_PROPOSAL_CANCELLED;
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
        saveEvent(order.getId().toString(), eventType, occurredAt, data, eventVersion(order));
    }

    private void saveEvent(String orderId, String eventType, OffsetDateTime occurredAt, Object data) {
        saveEvent(orderId, eventType, occurredAt, data, 1L);
    }

    private void saveEvent(String orderId, String eventType, OffsetDateTime occurredAt, Object data, long version) {
        UUID eventId = UUID.randomUUID();
        OrderEventEnvelope<Object> envelope = new OrderEventEnvelope<>(
                eventId.toString(),
                eventType,
                orderId,
                AGGREGATE_TYPE,
                version,
                iso(occurredAt),
                SOURCE,
                data
        );

        outboxEventRepository.save(OutboxEventEntity.builder()
                .id(eventId)
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(orderId)
                .eventType(eventType)
                .eventKey(orderId)
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
