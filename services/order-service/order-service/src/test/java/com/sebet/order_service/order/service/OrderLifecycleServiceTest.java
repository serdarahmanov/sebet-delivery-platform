package com.sebet.order_service.order.service;

import com.sebet.order_service.cache.service.OrderLifecycleRedisUpdater;
import com.sebet.order_service.order.event.OrderEventOutboxWriter;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OrderStatusHistoryEntity;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.persistence.repository.OrderStatusHistoryRepository;
import com.sebet.order_service.shared.enums.OrderCancellationReason;
import com.sebet.order_service.shared.enums.OrderCancelledBy;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ScheduleType;
import com.sebet.order_service.shared.exception.DriverNotAssignedException;
import com.sebet.order_service.shared.exception.OrderInvalidTransitionException;
import com.sebet.order_service.shared.exception.OrderNotFoundException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderLifecycleServiceTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderStatusHistoryRepository orderStatusHistoryRepository = mock(OrderStatusHistoryRepository.class);
    private final OrderLifecycleRedisUpdater orderLifecycleRedisUpdater = mock(OrderLifecycleRedisUpdater.class);
    private final OrderEventOutboxWriter orderEventOutboxWriter = mock(OrderEventOutboxWriter.class);

    private final OrderLifecycleService service = new OrderLifecycleService(
            orderRepository,
            orderStatusHistoryRepository,
            orderLifecycleRedisUpdater,
            orderEventOutboxWriter
    );

    @Test
    void storeAccept_movesPendingOrderToConfirmed() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.PENDING);
        when(orderRepository.findByIdAndStoreId(id, "store-1")).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);

        OrderLifecycleResult result = service.storeAccept(id.toString(), "store-1");

        assertThat(result.previousStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(result.newStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getCancelledAt()).isNull();

        ArgumentCaptor<OrderStatusHistoryEntity> historyCaptor =
                ArgumentCaptor.forClass(OrderStatusHistoryEntity.class);
        verify(orderStatusHistoryRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getFromStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(historyCaptor.getValue().getToStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(historyCaptor.getValue().getChangedByType()).isEqualTo("STORE");
        assertThat(historyCaptor.getValue().getChangedById()).isEqualTo("store-1");
        assertThat(historyCaptor.getValue().getReason()).isEqualTo("STORE_ACCEPTED");

        verify(orderLifecycleRedisUpdater).applyTransition(
                order,
                OrderStatus.CONFIRMED,
                result.changedAt().toString()
        );
        verify(orderEventOutboxWriter).saveOrderStatusTransition(
                order,
                OrderStatus.PENDING,
                OrderStatus.CONFIRMED,
                result.changedAt(),
                "STORE",
                "store-1",
                "STORE_ACCEPTED",
                null
        );
    }

    @Test
    void storeReject_movesPendingOrderToCancelledAndSetsCancellationFields() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.PENDING);
        when(orderRepository.findByIdAndStoreId(id, "store-1")).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);

        OrderLifecycleResult result = service.storeReject(
                id.toString(),
                "store-1",
                OrderCancellationReason.OUT_OF_STOCK
        );

        assertThat(result.newStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelledBy()).isEqualTo(OrderCancelledBy.STORE);
        assertThat(order.getCancellationReason()).isEqualTo(OrderCancellationReason.OUT_OF_STOCK);
        assertThat(order.getCancelledAt()).isNotNull();

        ArgumentCaptor<OrderStatusHistoryEntity> historyCaptor =
                ArgumentCaptor.forClass(OrderStatusHistoryEntity.class);
        verify(orderStatusHistoryRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getReason()).isEqualTo("OUT_OF_STOCK");
        verify(orderLifecycleRedisUpdater).applyTransition(
                order,
                OrderStatus.CANCELLED,
                result.changedAt().toString()
        );
        verify(orderEventOutboxWriter).saveOrderStatusTransition(
                order,
                OrderStatus.PENDING,
                OrderStatus.CANCELLED,
                result.changedAt(),
                "STORE",
                "store-1",
                "OUT_OF_STOCK",
                null
        );
    }

    @Test
    void storeMarkReady_movesConfirmedOrderToReadyForPickup() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.CONFIRMED);
        when(orderRepository.findByIdAndStoreId(id, "store-1")).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);

        OrderLifecycleResult result = service.storeMarkReady(id.toString(), "store-1");

        assertThat(result.previousStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(result.newStatus()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
        verify(orderLifecycleRedisUpdater).applyTransition(
                order,
                OrderStatus.READY_FOR_PICKUP,
                result.changedAt().toString()
        );
    }

    @Test
    void storeAccept_throwsConflictWhenOrderIsNotPending() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findByIdAndStoreId(id, "store-1"))
                .thenReturn(Optional.of(order(id, OrderStatus.CONFIRMED)));

        assertThatThrownBy(() -> service.storeAccept(id.toString(), "store-1"))
                .isInstanceOf(OrderInvalidTransitionException.class);

        verify(orderRepository, never()).saveAndFlush(any());
        verify(orderStatusHistoryRepository, never()).save(any());
        verify(orderLifecycleRedisUpdater, never()).applyTransition(any(), any(), any());
        verify(orderEventOutboxWriter, never()).saveOrderStatusTransition(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void storeMarkReady_throwsNotFoundWhenOrderDoesNotBelongToStore() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findByIdAndStoreId(id, "store-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.storeMarkReady(id.toString(), "store-1"))
                .isInstanceOf(OrderNotFoundException.class);

        verify(orderRepository, never()).saveAndFlush(any());
        verify(orderLifecycleRedisUpdater, never()).applyTransition(any(), any(), any());
    }

    @Test
    void storeReject_doesNotWriteHistoryOrRedisWhenOptimisticLockFails() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.PENDING);
        when(orderRepository.findByIdAndStoreId(id, "store-1")).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenThrow(new OptimisticLockingFailureException("stale order"));

        assertThatThrownBy(() -> service.storeReject(
                id.toString(),
                "store-1",
                OrderCancellationReason.OUT_OF_STOCK
        )).isInstanceOf(OptimisticLockingFailureException.class);

        verify(orderStatusHistoryRepository, never()).save(any());
        verify(orderLifecycleRedisUpdater, never()).applyTransition(any(), any(), any());
        verify(orderEventOutboxWriter, never()).saveOrderStatusTransition(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void storeReject_throwsNotFoundForInvalidOrderId() {
        assertThatThrownBy(() -> service.storeReject(
                "not-a-uuid",
                "store-1",
                OrderCancellationReason.STORE_REJECTED
        )).isInstanceOf(OrderNotFoundException.class);

        verify(orderRepository, never()).findByIdAndStoreId(any(), any());
    }

    // ── driver transitions ───────────────────────────────────────────────────

    @Test
    void driverPickup_movesReadyForPickupOrderToOutForDelivery() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.READY_FOR_PICKUP, "driver-1");
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);

        OrderLifecycleResult result = service.driverPickup(id.toString(), "driver-1");

        assertThat(result.previousStatus()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
        assertThat(result.newStatus()).isEqualTo(OrderStatus.OUT_FOR_DELIVERY);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.OUT_FOR_DELIVERY);

        ArgumentCaptor<OrderStatusHistoryEntity> historyCaptor =
                ArgumentCaptor.forClass(OrderStatusHistoryEntity.class);
        verify(orderStatusHistoryRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getChangedByType()).isEqualTo("DRIVER");
        assertThat(historyCaptor.getValue().getChangedById()).isEqualTo("driver-1");
        assertThat(historyCaptor.getValue().getReason()).isEqualTo("DRIVER_PICKED_UP");
        assertThat(historyCaptor.getValue().getMetadataJson()).isNull();

        verify(orderLifecycleRedisUpdater).applyTransition(
                order, OrderStatus.OUT_FOR_DELIVERY, result.changedAt().toString());
        verify(orderEventOutboxWriter).saveOrderStatusTransition(
                order,
                OrderStatus.READY_FOR_PICKUP,
                OrderStatus.OUT_FOR_DELIVERY,
                result.changedAt(),
                "DRIVER",
                "driver-1",
                "DRIVER_PICKED_UP",
                null
        );
    }

    @Test
    void driverArrive_movesOutForDeliveryOrderToArrivedAndPersistsMetadata() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.OUT_FOR_DELIVERY, "driver-1");
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);

        OrderLifecycleResult result = service.driverArrive(id.toString(), "driver-1", "{\"code\":\"07\"}");

        assertThat(result.newStatus()).isEqualTo(OrderStatus.ARRIVED);

        ArgumentCaptor<OrderStatusHistoryEntity> historyCaptor =
                ArgumentCaptor.forClass(OrderStatusHistoryEntity.class);
        verify(orderStatusHistoryRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getMetadataJson()).isEqualTo("{\"code\":\"07\"}");
        assertThat(historyCaptor.getValue().getReason()).isEqualTo("DRIVER_ARRIVED");
        verify(orderEventOutboxWriter).saveOrderStatusTransition(
                order,
                OrderStatus.OUT_FOR_DELIVERY,
                OrderStatus.ARRIVED,
                result.changedAt(),
                "DRIVER",
                "driver-1",
                "DRIVER_ARRIVED",
                "{\"code\":\"07\"}"
        );
    }

    @Test
    void driverComplete_movesArrivedOrderToDeliveredAndSetsDeliveredAt() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.ARRIVED, "driver-1");
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);

        OrderLifecycleResult result = service.driverComplete(id.toString(), "driver-1");

        assertThat(result.newStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.getDeliveredAt()).isNotNull();
        assertThat(order.getDeliveredAt()).isEqualTo(result.changedAt());

        ArgumentCaptor<OrderStatusHistoryEntity> historyCaptor =
                ArgumentCaptor.forClass(OrderStatusHistoryEntity.class);
        verify(orderStatusHistoryRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getReason()).isEqualTo("DRIVER_COMPLETED");
        assertThat(historyCaptor.getValue().getCreatedAt()).isEqualTo(result.changedAt());

        verify(orderLifecycleRedisUpdater).applyTransition(
                order, OrderStatus.DELIVERED, result.changedAt().toString());
    }

    @Test
    void driverPickup_throwsDriverNotAssignedWhenDriverDoesNotMatch() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id))
                .thenReturn(Optional.of(order(id, OrderStatus.READY_FOR_PICKUP, "other-driver")));

        assertThatThrownBy(() -> service.driverPickup(id.toString(), "driver-1"))
                .isInstanceOf(DriverNotAssignedException.class);

        verify(orderRepository, never()).saveAndFlush(any());
        verify(orderStatusHistoryRepository, never()).save(any());
        verify(orderLifecycleRedisUpdater, never()).applyTransition(any(), any(), any());
    }

    @Test
    void driverPickup_throwsDriverNotAssignedWhenOrderHasNoDriverAssigned() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id))
                .thenReturn(Optional.of(order(id, OrderStatus.READY_FOR_PICKUP, null)));

        assertThatThrownBy(() -> service.driverPickup(id.toString(), "driver-1"))
                .isInstanceOf(DriverNotAssignedException.class);

        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void driverPickup_throwsInvalidTransitionWhenNotReadyForPickup() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id))
                .thenReturn(Optional.of(order(id, OrderStatus.CONFIRMED, "driver-1")));

        assertThatThrownBy(() -> service.driverPickup(id.toString(), "driver-1"))
                .isInstanceOf(OrderInvalidTransitionException.class);

        verify(orderRepository, never()).saveAndFlush(any());
        verify(orderLifecycleRedisUpdater, never()).applyTransition(any(), any(), any());
    }

    @Test
    void driverPickup_throwsNotFoundWhenOrderDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.driverPickup(id.toString(), "driver-1"))
                .isInstanceOf(OrderNotFoundException.class);

        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void driverPickup_throwsNotFoundForInvalidOrderId() {
        assertThatThrownBy(() -> service.driverPickup("not-a-uuid", "driver-1"))
                .isInstanceOf(OrderNotFoundException.class);

        verify(orderRepository, never()).findById(any());
    }

    private OrderEntity order(UUID id, OrderStatus status) {
        return order(id, status, null);
    }

    private OrderEntity order(UUID id, OrderStatus status, String driverId) {
        OffsetDateTime now = OffsetDateTime.now();
        return OrderEntity.builder()
                .id(id)
                .customerId("customer-1")
                .storeId("store-1")
                .driverId(driverId)
                .cartId("cart-1")
                .status(status)
                .scheduleType(ScheduleType.ASAP)
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
}
