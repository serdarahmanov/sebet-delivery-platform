package com.sebet.order_service.scheduled.order;

import com.sebet.order_service.order.service.OrderLifecycleResult;
import com.sebet.order_service.order.service.OrderLifecycleService;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ScheduleType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledOrderActivationServiceTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderLifecycleService orderLifecycleService = mock(OrderLifecycleService.class);
    private final ScheduledOrderActivationService service =
            new ScheduledOrderActivationService(orderRepository, orderLifecycleService);

    @Test
    void activateDueOrdersQueriesScheduledOrdersUpToLeadTimeAndActivatesBatch() {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-20T10:00:00Z");
        UUID orderId = UUID.randomUUID();
        OrderEntity order = scheduledOrder(orderId, now.plusMinutes(20));
        OffsetDateTime changedAt = OffsetDateTime.parse("2026-07-20T10:00:05Z");
        when(orderRepository.findByStatusAndScheduledForLessThanEqualOrderByScheduledForAsc(
                eq(OrderStatus.SCHEDULED),
                eq(now.plusMinutes(30)),
                any(Pageable.class)
        )).thenReturn(List.of(order));
        when(orderLifecycleService.activateScheduledWithoutRedisUpdate(orderId.toString()))
                .thenReturn(new OrderLifecycleResult(
                        order,
                        OrderStatus.SCHEDULED,
                        OrderStatus.PENDING,
                        changedAt
                ));

        int activated = service.activateDueOrders(now, Duration.ofMinutes(30), 25);

        assertThat(activated).isEqualTo(1);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findByStatusAndScheduledForLessThanEqualOrderByScheduledForAsc(
                eq(OrderStatus.SCHEDULED),
                eq(now.plusMinutes(30)),
                pageableCaptor.capture()
        );
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(25);
        verify(orderLifecycleService).activateScheduledWithoutRedisUpdate(orderId.toString());
        verify(orderLifecycleService).updateScheduledActivationRedisViews(
                order,
                changedAt,
                OrderLifecycleService.SCHEDULED_ORDER_AUTO_ACTIVATION_ACTION,
                "scheduled-order-activation:" + orderId
        );
    }

    @Test
    void activateDueOrdersContinuesWhenOneOrderFails() {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-20T10:00:00Z");
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        OrderEntity first = scheduledOrder(firstId, now.plusMinutes(5));
        OrderEntity second = scheduledOrder(secondId, now.plusMinutes(10));
        when(orderRepository.findByStatusAndScheduledForLessThanEqualOrderByScheduledForAsc(
                eq(OrderStatus.SCHEDULED),
                eq(now.plusMinutes(30)),
                any(Pageable.class)
        )).thenReturn(List.of(first, second));
        when(orderLifecycleService.activateScheduledWithoutRedisUpdate(firstId.toString()))
                .thenThrow(new IllegalStateException("stale status"));
        when(orderLifecycleService.activateScheduledWithoutRedisUpdate(secondId.toString()))
                .thenReturn(new OrderLifecycleResult(
                        second,
                        OrderStatus.SCHEDULED,
                        OrderStatus.PENDING,
                        now
                ));

        int activated = service.activateDueOrders(now, Duration.ofMinutes(30), 10);

        assertThat(activated).isEqualTo(1);
        verify(orderLifecycleService).activateScheduledWithoutRedisUpdate(firstId.toString());
        verify(orderLifecycleService).activateScheduledWithoutRedisUpdate(secondId.toString());
        verify(orderLifecycleService).updateScheduledActivationRedisViews(
                eq(second),
                eq(now),
                eq(OrderLifecycleService.SCHEDULED_ORDER_AUTO_ACTIVATION_ACTION),
                eq("scheduled-order-activation:" + secondId)
        );
    }

    @Test
    void activateDueOrdersCountsDbActivationEvenWhenRedisUpdateFails() {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-20T10:00:00Z");
        UUID orderId = UUID.randomUUID();
        OrderEntity order = scheduledOrder(orderId, now.plusMinutes(20));
        when(orderRepository.findByStatusAndScheduledForLessThanEqualOrderByScheduledForAsc(
                eq(OrderStatus.SCHEDULED),
                eq(now.plusMinutes(30)),
                any(Pageable.class)
        )).thenReturn(List.of(order));
        when(orderLifecycleService.activateScheduledWithoutRedisUpdate(orderId.toString()))
                .thenReturn(new OrderLifecycleResult(order, OrderStatus.SCHEDULED, OrderStatus.PENDING, now));
        org.mockito.Mockito.doThrow(new IllegalStateException("redis"))
                .when(orderLifecycleService)
                .updateScheduledActivationRedisViews(any(), any(), any(), any());

        int activated = service.activateDueOrders(now, Duration.ofMinutes(30), 10);

        assertThat(activated).isEqualTo(1);
    }

    @Test
    void activateDueOrdersRejectsInvalidBatchSize() {
        assertThatThrownBy(() -> service.activateDueOrders(
                OffsetDateTime.parse("2026-07-20T10:00:00Z"),
                Duration.ofMinutes(30),
                0
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");

        verify(orderRepository, never()).findByStatusAndScheduledForLessThanEqualOrderByScheduledForAsc(
                any(), any(), any());
    }

    private OrderEntity scheduledOrder(UUID id, OffsetDateTime scheduledFor) {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-20T09:00:00Z");
        return OrderEntity.builder()
                .id(id)
                .customerId("customer-1")
                .storeId("store-1")
                .cartId("cart-1")
                .status(OrderStatus.SCHEDULED)
                .scheduleType(ScheduleType.SCHEDULED)
                .scheduledFor(scheduledFor)
                .subtotalAmount(new BigDecimal("10000.00"))
                .itemDiscountAmount(BigDecimal.ZERO)
                .orderDiscountAmount(BigDecimal.ZERO)
                .deliveryFeeAmount(new BigDecimal("3000.00"))
                .totalAmount(new BigDecimal("13000.00"))
                .currency("UZS")
                .deliveryAddressJson("{}")
                .storeLat(new BigDecimal("41.311100"))
                .storeLng(new BigDecimal("69.279700"))
                .selectedPromoCodes(List.of())
                .serviceFeeAmount(BigDecimal.ZERO)
                .smallOrderFeeAmount(BigDecimal.ZERO)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
