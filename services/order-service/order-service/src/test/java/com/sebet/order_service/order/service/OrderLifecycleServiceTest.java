package com.sebet.order_service.order.service;

import com.sebet.order_service.cache.service.OrderLifecycleRedisUpdater;
import com.sebet.order_service.cache.service.OrderCancelActiveProposalRedisUpdater;
import com.sebet.order_service.cache.service.OrderProposeChangesRedisUpdater;
import com.sebet.order_service.order.event.OrderEventOutboxWriter;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OrderProposalEntity;
import com.sebet.order_service.persistence.entity.OrderStatusHistoryEntity;
import com.sebet.order_service.persistence.repository.OrderProposalRepository;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.persistence.repository.OrderStatusHistoryRepository;
import com.sebet.order_service.shared.enums.OrderCancellationReason;
import com.sebet.order_service.shared.enums.OrderCancelledBy;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ProposalStatus;
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
    private final OrderProposalRepository orderProposalRepository = mock(OrderProposalRepository.class);
    private final OrderProposeChangesRedisUpdater orderProposeChangesRedisUpdater = mock(OrderProposeChangesRedisUpdater.class);
    private final OrderCancelActiveProposalRedisUpdater orderCancelActiveProposalRedisUpdater =
            mock(OrderCancelActiveProposalRedisUpdater.class);

    private final OrderLifecycleService service = new OrderLifecycleService(
            orderRepository,
            orderStatusHistoryRepository,
            orderLifecycleRedisUpdater,
            orderEventOutboxWriter,
            orderProposalRepository,
            orderProposeChangesRedisUpdater,
            orderCancelActiveProposalRedisUpdater
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
    void activateScheduled_movesScheduledOrderToPendingAndWritesHistory() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.SCHEDULED);
        order.setScheduleType(ScheduleType.SCHEDULED);
        order.setScheduledFor(OffsetDateTime.now().plusHours(2));
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);

        OrderLifecycleResult result = service.activateScheduled(id.toString());

        assertThat(result.previousStatus()).isEqualTo(OrderStatus.SCHEDULED);
        assertThat(result.newStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);

        ArgumentCaptor<OrderStatusHistoryEntity> historyCaptor =
                ArgumentCaptor.forClass(OrderStatusHistoryEntity.class);
        verify(orderStatusHistoryRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getFromStatus()).isEqualTo(OrderStatus.SCHEDULED);
        assertThat(historyCaptor.getValue().getToStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(historyCaptor.getValue().getChangedByType()).isEqualTo("SYSTEM");
        assertThat(historyCaptor.getValue().getReason()).isEqualTo("SCHEDULED_ACTIVATED");

        verify(orderLifecycleRedisUpdater).applyTransition(
                order,
                OrderStatus.PENDING,
                result.changedAt().toString(),
                OrderLifecycleService.INTERNAL_ACTIVATE_SCHEDULED_ACTION
        );
        verify(orderEventOutboxWriter).saveOrderStatusTransition(
                order,
                OrderStatus.SCHEDULED,
                OrderStatus.PENDING,
                result.changedAt(),
                "SYSTEM",
                null,
                "SCHEDULED_ACTIVATED",
                null
        );
    }

    @Test
    void activateScheduled_throwsInvalidTransitionWhenOrderIsNotScheduled() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id)).thenReturn(Optional.of(order(id, OrderStatus.CONFIRMED)));

        assertThatThrownBy(() -> service.activateScheduled(id.toString()))
                .isInstanceOf(OrderInvalidTransitionException.class);

        verify(orderRepository, never()).saveAndFlush(any());
        verify(orderStatusHistoryRepository, never()).save(any());
        verify(orderLifecycleRedisUpdater, never()).applyTransition(any(), any(), any(), any());
        verify(orderEventOutboxWriter, never()).saveOrderStatusTransition(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void storeCancel_movesConfirmedOrderToCancelledAndSetsCancellationFields() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.CONFIRMED);
        when(orderRepository.findByIdAndStoreId(id, "store-1")).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);

        OrderLifecycleResult result = service.storeCancel(
                id.toString(),
                "store-1",
                OrderCancellationReason.STORE_CLOSED,
                "{\"note\":\"closed early\"}"
        );

        assertThat(result.previousStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(result.newStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelledBy()).isEqualTo(OrderCancelledBy.STORE);
        assertThat(order.getCancellationReason()).isEqualTo(OrderCancellationReason.STORE_CLOSED);
        assertThat(order.getCancelledAt()).isEqualTo(result.changedAt());

        ArgumentCaptor<OrderStatusHistoryEntity> historyCaptor =
                ArgumentCaptor.forClass(OrderStatusHistoryEntity.class);
        verify(orderStatusHistoryRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getFromStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(historyCaptor.getValue().getToStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(historyCaptor.getValue().getReason()).isEqualTo("STORE_CLOSED");
        assertThat(historyCaptor.getValue().getMetadataJson()).isEqualTo("{\"note\":\"closed early\"}");

        verify(orderLifecycleRedisUpdater).applyTransition(
                order,
                OrderStatus.CANCELLED,
                result.changedAt().toString(),
                "STORE_CANCEL_ORDER"
        );
        verify(orderEventOutboxWriter).saveOrderStatusTransition(
                order,
                OrderStatus.CONFIRMED,
                OrderStatus.CANCELLED,
                result.changedAt(),
                "STORE",
                "store-1",
                "STORE_CLOSED",
                "{\"note\":\"closed early\"}"
        );
    }

    @Test
    void storeCancel_allowsAwaitingCustomerResponseOrder() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.AWAITING_CUSTOMER_RESPONSE);
        when(orderRepository.findByIdAndStoreId(id, "store-1")).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);

        OrderLifecycleResult result = service.storeCancel(
                id.toString(),
                "store-1",
                OrderCancellationReason.STORE_UNABLE_TO_FULFIL,
                "{\"note\":null}"
        );

        assertThat(result.previousStatus()).isEqualTo(OrderStatus.AWAITING_CUSTOMER_RESPONSE);
        assertThat(result.newStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void storeCancel_marksProposalStoreCancelledWhenOrderHadActiveProposal() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.AWAITING_CUSTOMER_RESPONSE);
        OrderProposalEntity proposal = OrderProposalEntity.builder()
                .id(UUID.randomUUID())
                .orderId(id)
                .storeId("store-1")
                .proposedAt(OffsetDateTime.parse("2026-07-09T10:00:00Z"))
                .itemsJson("[{\"productId\":\"p1\"}]")
                .build();
        when(orderRepository.findByIdAndStoreId(id, "store-1")).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);
        when(orderProposalRepository.findByOrderId(id)).thenReturn(Optional.of(proposal));
        when(orderProposalRepository.saveAndFlush(proposal)).thenReturn(proposal);

        service.storeCancel(id.toString(), "store-1", OrderCancellationReason.STORE_UNABLE_TO_FULFIL, null);

        assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.STORE_CANCELLED);
        verify(orderProposalRepository, never()).delete(any());
    }

    @Test
    void systemCancel_movesScheduledOrderToCancelledAndSetsSystemFields() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.SCHEDULED);
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);

        OrderLifecycleResult result = service.systemCancelWithoutRedisUpdate(
                id.toString(),
                OrderCancellationReason.PAYMENT_FAILED,
                "{\"reason\":\"PAYMENT_FAILED\"}"
        );

        assertThat(result.previousStatus()).isEqualTo(OrderStatus.SCHEDULED);
        assertThat(result.newStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelledBy()).isEqualTo(OrderCancelledBy.SYSTEM);
        assertThat(order.getCancellationReason()).isEqualTo(OrderCancellationReason.PAYMENT_FAILED);
        assertThat(order.getCancelledAt()).isEqualTo(result.changedAt());

        ArgumentCaptor<OrderStatusHistoryEntity> historyCaptor =
                ArgumentCaptor.forClass(OrderStatusHistoryEntity.class);
        verify(orderStatusHistoryRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getFromStatus()).isEqualTo(OrderStatus.SCHEDULED);
        assertThat(historyCaptor.getValue().getToStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(historyCaptor.getValue().getChangedByType()).isEqualTo("SYSTEM");
        assertThat(historyCaptor.getValue().getReason()).isEqualTo("PAYMENT_FAILED");
        assertThat(historyCaptor.getValue().getMetadataJson()).isEqualTo("{\"reason\":\"PAYMENT_FAILED\"}");

        verify(orderEventOutboxWriter).saveOrderStatusTransition(
                order,
                OrderStatus.SCHEDULED,
                OrderStatus.CANCELLED,
                result.changedAt(),
                "SYSTEM",
                null,
                "PAYMENT_FAILED",
                "{\"reason\":\"PAYMENT_FAILED\"}"
        );
        verify(orderLifecycleRedisUpdater, never()).applyTransition(any(), any(), any(), any(), any());
    }

    @Test
    void systemCancel_marksProposalSystemCancelledWhenOrderHadActiveProposal() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.AWAITING_CUSTOMER_RESPONSE);
        OrderProposalEntity proposal = OrderProposalEntity.builder()
                .id(UUID.randomUUID())
                .orderId(id)
                .storeId("store-1")
                .proposedAt(OffsetDateTime.parse("2026-07-09T10:00:00Z"))
                .itemsJson("[{\"productId\":\"p1\"}]")
                .build();
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);
        when(orderProposalRepository.findByOrderId(id)).thenReturn(Optional.of(proposal));
        when(orderProposalRepository.saveAndFlush(proposal)).thenReturn(proposal);

        service.systemCancelWithoutRedisUpdate(
                id.toString(), OrderCancellationReason.AWAITING_CUSTOMER_RESPONSE_TIMEOUT, null);

        assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.SYSTEM_CANCELLED);
        verify(orderProposalRepository, never()).delete(any());
    }

    @Test
    void adminCancel_marksProposalSystemCancelledWhenOrderHadActiveProposal() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.AWAITING_CUSTOMER_RESPONSE);
        OrderProposalEntity proposal = OrderProposalEntity.builder()
                .id(UUID.randomUUID())
                .orderId(id)
                .storeId("store-1")
                .proposedAt(OffsetDateTime.parse("2026-07-09T10:00:00Z"))
                .itemsJson("[{\"productId\":\"p1\"}]")
                .build();
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);
        when(orderProposalRepository.findByOrderId(id)).thenReturn(Optional.of(proposal));
        when(orderProposalRepository.saveAndFlush(proposal)).thenReturn(proposal);

        service.adminCancelWithoutRedisUpdate(
                id.toString(), OrderCancellationReason.SYSTEM_ERROR, null);

        assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.SYSTEM_CANCELLED);
        verify(orderProposalRepository, never()).delete(any());
    }

    @Test
    void systemCancel_rejectsOrderInTransit() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id)).thenReturn(Optional.of(order(id, OrderStatus.OUT_FOR_DELIVERY)));

        assertThatThrownBy(() -> service.systemCancelWithoutRedisUpdate(
                id.toString(),
                OrderCancellationReason.SYSTEM_ERROR,
                null
        )).isInstanceOf(OrderInvalidTransitionException.class);

        verify(orderRepository, never()).saveAndFlush(any());
        verify(orderStatusHistoryRepository, never()).save(any());
        verify(orderEventOutboxWriter, never()).saveOrderStatusTransition(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void adminCancel_allowsOrderInTransit() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.ARRIVED);
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);

        OrderLifecycleResult result = service.adminCancelWithoutRedisUpdate(
                id.toString(),
                OrderCancellationReason.SYSTEM_ERROR,
                "{\"reason\":\"SYSTEM_ERROR\"}"
        );

        assertThat(result.previousStatus()).isEqualTo(OrderStatus.ARRIVED);
        assertThat(result.newStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelledBy()).isEqualTo(OrderCancelledBy.SYSTEM);
        assertThat(order.getCancellationReason()).isEqualTo(OrderCancellationReason.SYSTEM_ERROR);

        verify(orderEventOutboxWriter).saveOrderStatusTransition(
                order,
                OrderStatus.ARRIVED,
                OrderStatus.CANCELLED,
                result.changedAt(),
                "SYSTEM",
                null,
                "SYSTEM_ERROR",
                "{\"reason\":\"SYSTEM_ERROR\"}"
        );
    }

    @Test
    void adminCancel_rejectsDeliveredOrder() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id)).thenReturn(Optional.of(order(id, OrderStatus.DELIVERED)));

        assertThatThrownBy(() -> service.adminCancelWithoutRedisUpdate(
                id.toString(),
                OrderCancellationReason.SYSTEM_ERROR,
                null
        )).isInstanceOf(OrderInvalidTransitionException.class);

        verify(orderRepository, never()).saveAndFlush(any());
        verify(orderStatusHistoryRepository, never()).save(any());
        verify(orderEventOutboxWriter, never()).saveOrderStatusTransition(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void adminCancel_rejectsAlreadyCancelledOrder() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id)).thenReturn(Optional.of(order(id, OrderStatus.CANCELLED)));

        assertThatThrownBy(() -> service.adminCancelWithoutRedisUpdate(
                id.toString(),
                OrderCancellationReason.SYSTEM_ERROR,
                null
        )).isInstanceOf(OrderInvalidTransitionException.class);

        verify(orderRepository, never()).saveAndFlush(any());
        verify(orderStatusHistoryRepository, never()).save(any());
        verify(orderEventOutboxWriter, never()).saveOrderStatusTransition(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void evictSystemCancelledRedisViews_usesCancelledHotViewFallbackPattern() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.CANCELLED);
        order.setCancelledAt(OffsetDateTime.parse("2026-07-10T10:00:00Z"));
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));

        service.evictSystemCancelledRedisViews(id.toString(), "idem-1");

        verify(orderLifecycleRedisUpdater).applyTransition(
                order,
                OrderStatus.CANCELLED,
                "2026-07-10T10:00Z",
                OrderLifecycleService.INTERNAL_SYSTEM_CANCEL_ACTION,
                "idem-1"
        );
    }

    @Test
    void evictAdminCancelledRedisViews_usesAdminCancelSourceAction() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.CANCELLED);
        order.setCancelledAt(OffsetDateTime.parse("2026-07-10T10:00:00Z"));
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));

        service.evictAdminCancelledRedisViews(id.toString(), "idem-1");

        verify(orderLifecycleRedisUpdater).applyTransition(
                order,
                OrderStatus.CANCELLED,
                "2026-07-10T10:00Z",
                OrderLifecycleService.INTERNAL_ADMIN_CANCEL_ACTION,
                "idem-1"
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
    void storeCancel_throwsConflictWhenOrderIsNotCancellableByStore() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findByIdAndStoreId(id, "store-1"))
                .thenReturn(Optional.of(order(id, OrderStatus.PENDING)));

        assertThatThrownBy(() -> service.storeCancel(
                id.toString(),
                "store-1",
                OrderCancellationReason.STORE_CLOSED,
                null
        )).isInstanceOf(OrderInvalidTransitionException.class);

        verify(orderRepository, never()).saveAndFlush(any());
        verify(orderStatusHistoryRepository, never()).save(any());
        verify(orderLifecycleRedisUpdater, never()).applyTransition(any(), any(), any(), any());
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

    // ── storeProposeChangesWithoutRedisUpdate ─────────────────────────────────

    @Test
    void storeProposeChangesWithoutRedisUpdate_transitionsConfirmedToAwaitingAndPersistsAll() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.CONFIRMED);
        when(orderRepository.findByIdAndStoreId(id, "store-1")).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);
        OffsetDateTime proposedAt = OffsetDateTime.parse("2026-07-09T10:00:00Z");

        OrderLifecycleResult result = service.storeProposeChangesWithoutRedisUpdate(
                id.toString(), "store-1", "[{\"productId\":\"p1\"}]", proposedAt);

        assertThat(result.previousStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(result.newStatus()).isEqualTo(OrderStatus.AWAITING_CUSTOMER_RESPONSE);
        assertThat(result.changedAt()).isEqualTo(proposedAt);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.AWAITING_CUSTOMER_RESPONSE);
        assertThat(order.getUpdatedAt()).isEqualTo(proposedAt);

        ArgumentCaptor<OrderStatusHistoryEntity> historyCaptor =
                ArgumentCaptor.forClass(OrderStatusHistoryEntity.class);
        verify(orderStatusHistoryRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getFromStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(historyCaptor.getValue().getToStatus()).isEqualTo(OrderStatus.AWAITING_CUSTOMER_RESPONSE);
        assertThat(historyCaptor.getValue().getChangedByType()).isEqualTo("STORE");
        assertThat(historyCaptor.getValue().getChangedById()).isEqualTo("store-1");
        assertThat(historyCaptor.getValue().getReason()).isEqualTo("STORE_PROPOSED_CHANGES");
        assertThat(historyCaptor.getValue().getCreatedAt()).isEqualTo(proposedAt);

        ArgumentCaptor<OrderProposalEntity> proposalCaptor =
                ArgumentCaptor.forClass(OrderProposalEntity.class);
        verify(orderProposalRepository).save(proposalCaptor.capture());
        assertThat(proposalCaptor.getValue().getOrderId()).isEqualTo(id);
        assertThat(proposalCaptor.getValue().getStoreId()).isEqualTo("store-1");
        assertThat(proposalCaptor.getValue().getProposedAt()).isEqualTo(proposedAt);
        assertThat(proposalCaptor.getValue().getItemsJson()).isEqualTo("[{\"productId\":\"p1\"}]");

        verify(orderEventOutboxWriter).saveOrderProposedToCustomer(
                order, "[{\"productId\":\"p1\"}]", proposedAt);
    }

    @Test
    void storeProposeChangesWithoutRedisUpdate_throwsInvalidTransitionWhenNotConfirmed() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findByIdAndStoreId(id, "store-1"))
                .thenReturn(Optional.of(order(id, OrderStatus.AWAITING_CUSTOMER_RESPONSE)));

        assertThatThrownBy(() -> service.storeProposeChangesWithoutRedisUpdate(
                id.toString(), "store-1", "[]", OffsetDateTime.now()))
                .isInstanceOf(OrderInvalidTransitionException.class);

        verify(orderRepository, never()).saveAndFlush(any());
        verify(orderStatusHistoryRepository, never()).save(any());
        verify(orderProposalRepository, never()).save(any());
        verify(orderEventOutboxWriter, never()).saveOrderProposedToCustomer(any(), any(), any());
    }

    @Test
    void storeProposeChangesWithoutRedisUpdate_throwsNotFoundWhenOrderDoesNotBelongToStore() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findByIdAndStoreId(id, "store-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.storeProposeChangesWithoutRedisUpdate(
                id.toString(), "store-1", "[]", OffsetDateTime.now()))
                .isInstanceOf(OrderNotFoundException.class);

        verify(orderRepository, never()).saveAndFlush(any());
    }

    // ── updateProposeChangesRedisViews ────────────────────────────────────────

    @Test
    void updateProposeChangesRedisViews_loadsOrderAndProposalAndCallsUpdater() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.AWAITING_CUSTOMER_RESPONSE);
        OrderProposalEntity proposal = OrderProposalEntity.builder()
                .id(UUID.randomUUID())
                .orderId(id)
                .storeId("store-1")
                .proposedAt(OffsetDateTime.parse("2026-07-09T10:00:00Z"))
                .itemsJson("[{\"productId\":\"p1\"}]")
                .build();
        when(orderRepository.findByIdAndStoreId(id, "store-1")).thenReturn(Optional.of(order));
        when(orderProposalRepository.findByOrderId(id)).thenReturn(Optional.of(proposal));

        service.updateProposeChangesRedisViews(id.toString(), "store-1", "idem-1");

        verify(orderProposeChangesRedisUpdater).apply(order, proposal, "idem-1");
    }

    @Test
    void updateProposeChangesRedisViews_throwsNotFoundWhenProposalIsMissing() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findByIdAndStoreId(id, "store-1"))
                .thenReturn(Optional.of(order(id, OrderStatus.AWAITING_CUSTOMER_RESPONSE)));
        when(orderProposalRepository.findByOrderId(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateProposeChangesRedisViews(id.toString(), "store-1", "idem-1"))
                .isInstanceOf(OrderNotFoundException.class);

        verify(orderProposeChangesRedisUpdater, never()).apply(any(), any(), any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @Test
    void cancelActiveProposal_movesOrderBackToConfirmedAndMarksProposalCancelled() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.AWAITING_CUSTOMER_RESPONSE);
        OrderProposalEntity proposal = OrderProposalEntity.builder()
                .id(UUID.randomUUID())
                .orderId(id)
                .storeId("store-1")
                .proposedAt(OffsetDateTime.parse("2026-07-09T10:00:00Z"))
                .itemsJson("[{\"productId\":\"p1\"}]")
                .build();
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderProposalRepository.findByOrderId(id)).thenReturn(Optional.of(proposal));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);
        when(orderProposalRepository.saveAndFlush(proposal)).thenReturn(proposal);

        OrderLifecycleResult result = service.cancelActiveProposalWithoutRedisUpdate(id.toString());

        assertThat(result.previousStatus()).isEqualTo(OrderStatus.AWAITING_CUSTOMER_RESPONSE);
        assertThat(result.newStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.CANCELLED);
        verify(orderProposalRepository, never()).delete(any());

        ArgumentCaptor<OrderStatusHistoryEntity> historyCaptor =
                ArgumentCaptor.forClass(OrderStatusHistoryEntity.class);
        verify(orderStatusHistoryRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getFromStatus()).isEqualTo(OrderStatus.AWAITING_CUSTOMER_RESPONSE);
        assertThat(historyCaptor.getValue().getToStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(historyCaptor.getValue().getChangedByType()).isEqualTo("SYSTEM");
        assertThat(historyCaptor.getValue().getReason()).isEqualTo("ACTIVE_PROPOSAL_CANCELLED");

        verify(orderEventOutboxWriter).saveOrderStatusTransition(
                order,
                OrderStatus.AWAITING_CUSTOMER_RESPONSE,
                OrderStatus.CONFIRMED,
                result.changedAt(),
                "SYSTEM",
                null,
                "ACTIVE_PROPOSAL_CANCELLED",
                null
        );
    }

    @Test
    void cancelActiveProposal_rejectsOrderWithoutActiveProposalStatus() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id)).thenReturn(Optional.of(order(id, OrderStatus.CONFIRMED)));

        assertThatThrownBy(() -> service.cancelActiveProposalWithoutRedisUpdate(id.toString()))
                .isInstanceOf(OrderInvalidTransitionException.class);

        verify(orderProposalRepository, never()).findByOrderId(any());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void cancelActiveProposal_throwsNotFoundWhenProposalMissing() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id)).thenReturn(Optional.of(order(id, OrderStatus.AWAITING_CUSTOMER_RESPONSE)));
        when(orderProposalRepository.findByOrderId(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelActiveProposalWithoutRedisUpdate(id.toString()))
                .isInstanceOf(OrderNotFoundException.class);

        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void cancelProposalAndOrder_cancelsOrderAndRetainsProposalRowForAudit() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.AWAITING_CUSTOMER_RESPONSE);
        OrderProposalEntity proposal = OrderProposalEntity.builder()
                .id(UUID.randomUUID())
                .orderId(id)
                .storeId("store-1")
                .proposedAt(OffsetDateTime.parse("2026-07-09T10:00:00Z"))
                .itemsJson("[{\"productId\":\"p1\"}]")
                .build();
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));
        when(orderProposalRepository.findByOrderId(id)).thenReturn(Optional.of(proposal));
        when(orderRepository.saveAndFlush(order)).thenReturn(order);
        when(orderProposalRepository.saveAndFlush(proposal)).thenReturn(proposal);

        OrderLifecycleResult result = service.cancelProposalAndOrderWithoutRedisUpdate(id.toString());

        assertThat(result.previousStatus()).isEqualTo(OrderStatus.AWAITING_CUSTOMER_RESPONSE);
        assertThat(result.newStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelledAt()).isNotNull();
        assertThat(order.getCancelledBy()).isEqualTo(OrderCancelledBy.SYSTEM);
        assertThat(order.getCancellationReason()).isEqualTo(OrderCancellationReason.AWAITING_CUSTOMER_RESPONSE_TIMEOUT);
        assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.TIMED_OUT);
        verify(orderProposalRepository, never()).delete(any());

        ArgumentCaptor<OrderStatusHistoryEntity> historyCaptor =
                ArgumentCaptor.forClass(OrderStatusHistoryEntity.class);
        verify(orderStatusHistoryRepository).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getFromStatus()).isEqualTo(OrderStatus.AWAITING_CUSTOMER_RESPONSE);
        assertThat(historyCaptor.getValue().getToStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(historyCaptor.getValue().getChangedByType()).isEqualTo("SYSTEM");
        assertThat(historyCaptor.getValue().getReason()).isEqualTo("AWAITING_CUSTOMER_RESPONSE_TIMEOUT");

        verify(orderEventOutboxWriter).saveOrderStatusTransition(
                order,
                OrderStatus.AWAITING_CUSTOMER_RESPONSE,
                OrderStatus.CANCELLED,
                result.changedAt(),
                "SYSTEM",
                null,
                "AWAITING_CUSTOMER_RESPONSE_TIMEOUT",
                null
        );
    }

    @Test
    void cancelProposalAndOrder_throwsInvalidTransitionWhenNotAwaitingCustomerResponse() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id)).thenReturn(Optional.of(order(id, OrderStatus.CONFIRMED)));

        assertThatThrownBy(() -> service.cancelProposalAndOrderWithoutRedisUpdate(id.toString()))
                .isInstanceOf(OrderInvalidTransitionException.class);

        verify(orderProposalRepository, never()).findByOrderId(any());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void cancelProposalAndOrder_throwsNotFoundWhenProposalMissing() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id)).thenReturn(Optional.of(order(id, OrderStatus.AWAITING_CUSTOMER_RESPONSE)));
        when(orderProposalRepository.findByOrderId(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelProposalAndOrderWithoutRedisUpdate(id.toString()))
                .isInstanceOf(OrderNotFoundException.class);

        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateCancelActiveProposalRedisViews_loadsOrderAndCallsUpdater() {
        UUID id = UUID.randomUUID();
        OrderEntity order = order(id, OrderStatus.CONFIRMED);
        when(orderRepository.findById(id)).thenReturn(Optional.of(order));

        service.updateCancelActiveProposalRedisViews(id.toString(), "idem-1");

        verify(orderCancelActiveProposalRedisUpdater).apply(order, "idem-1");
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
