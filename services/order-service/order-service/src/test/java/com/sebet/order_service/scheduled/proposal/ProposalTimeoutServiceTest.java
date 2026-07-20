package com.sebet.order_service.scheduled.proposal;

import com.sebet.order_service.order.service.OrderLifecycleResult;
import com.sebet.order_service.order.service.OrderLifecycleService;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.shared.enums.OrderStatus;
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

class ProposalTimeoutServiceTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderLifecycleService orderLifecycleService = mock(OrderLifecycleService.class);
    private final ProposalTimeoutService service =
            new ProposalTimeoutService(orderRepository, orderLifecycleService);

    @Test
    void cancelExpiredProposalsQueriesAwaitingOrdersOlderThanResponseWindowAndCancelsBatch() {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-20T10:00:00Z");
        UUID orderId = UUID.randomUUID();
        OrderEntity order = awaitingOrder(orderId, now.minusHours(2));
        OffsetDateTime changedAt = OffsetDateTime.parse("2026-07-20T10:00:05Z");
        when(orderRepository.findByStatusAndUpdatedAtLessThanEqualOrderByUpdatedAtAsc(
                eq(OrderStatus.AWAITING_CUSTOMER_RESPONSE),
                eq(now.minusHours(1)),
                any(Pageable.class)
        )).thenReturn(List.of(order));
        when(orderLifecycleService.cancelProposalAndOrderWithoutRedisUpdate(orderId.toString()))
                .thenReturn(new OrderLifecycleResult(
                        order,
                        OrderStatus.AWAITING_CUSTOMER_RESPONSE,
                        OrderStatus.CANCELLED,
                        changedAt
                ));

        int cancelled = service.cancelExpiredProposals(now, Duration.ofHours(1), 25);

        assertThat(cancelled).isEqualTo(1);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findByStatusAndUpdatedAtLessThanEqualOrderByUpdatedAtAsc(
                eq(OrderStatus.AWAITING_CUSTOMER_RESPONSE),
                eq(now.minusHours(1)),
                pageableCaptor.capture()
        );
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(25);
        verify(orderLifecycleService).cancelProposalAndOrderWithoutRedisUpdate(orderId.toString());
        verify(orderLifecycleService).evictCancelProposalAndOrderRedisViews(
                order,
                changedAt,
                "proposal-timeout:" + orderId
        );
    }

    @Test
    void cancelExpiredProposalsContinuesWhenOneOrderFails() {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-20T10:00:00Z");
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        OrderEntity first = awaitingOrder(firstId, now.minusHours(3));
        OrderEntity second = awaitingOrder(secondId, now.minusHours(2));
        when(orderRepository.findByStatusAndUpdatedAtLessThanEqualOrderByUpdatedAtAsc(
                eq(OrderStatus.AWAITING_CUSTOMER_RESPONSE),
                eq(now.minusHours(1)),
                any(Pageable.class)
        )).thenReturn(List.of(first, second));
        when(orderLifecycleService.cancelProposalAndOrderWithoutRedisUpdate(firstId.toString()))
                .thenThrow(new IllegalStateException("stale status"));
        when(orderLifecycleService.cancelProposalAndOrderWithoutRedisUpdate(secondId.toString()))
                .thenReturn(new OrderLifecycleResult(
                        second,
                        OrderStatus.AWAITING_CUSTOMER_RESPONSE,
                        OrderStatus.CANCELLED,
                        now
                ));

        int cancelled = service.cancelExpiredProposals(now, Duration.ofHours(1), 10);

        assertThat(cancelled).isEqualTo(1);
        verify(orderLifecycleService).cancelProposalAndOrderWithoutRedisUpdate(firstId.toString());
        verify(orderLifecycleService).cancelProposalAndOrderWithoutRedisUpdate(secondId.toString());
        verify(orderLifecycleService).evictCancelProposalAndOrderRedisViews(
                eq(second),
                eq(now),
                eq("proposal-timeout:" + secondId)
        );
    }

    @Test
    void cancelExpiredProposalsCountsDbCancellationEvenWhenRedisEvictionFails() {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-20T10:00:00Z");
        UUID orderId = UUID.randomUUID();
        OrderEntity order = awaitingOrder(orderId, now.minusHours(2));
        when(orderRepository.findByStatusAndUpdatedAtLessThanEqualOrderByUpdatedAtAsc(
                eq(OrderStatus.AWAITING_CUSTOMER_RESPONSE),
                eq(now.minusHours(1)),
                any(Pageable.class)
        )).thenReturn(List.of(order));
        when(orderLifecycleService.cancelProposalAndOrderWithoutRedisUpdate(orderId.toString()))
                .thenReturn(new OrderLifecycleResult(
                        order, OrderStatus.AWAITING_CUSTOMER_RESPONSE, OrderStatus.CANCELLED, now));
        org.mockito.Mockito.doThrow(new IllegalStateException("redis"))
                .when(orderLifecycleService)
                .evictCancelProposalAndOrderRedisViews(any(OrderEntity.class), any(), any());

        int cancelled = service.cancelExpiredProposals(now, Duration.ofHours(1), 10);

        assertThat(cancelled).isEqualTo(1);
    }

    @Test
    void cancelExpiredProposalsRejectsInvalidBatchSize() {
        assertThatThrownBy(() -> service.cancelExpiredProposals(
                OffsetDateTime.parse("2026-07-20T10:00:00Z"),
                Duration.ofHours(1),
                0
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");

        verify(orderRepository, never()).findByStatusAndUpdatedAtLessThanEqualOrderByUpdatedAtAsc(
                any(), any(), any());
    }

    private OrderEntity awaitingOrder(UUID id, OffsetDateTime updatedAt) {
        OffsetDateTime createdAt = updatedAt.minusMinutes(10);
        return OrderEntity.builder()
                .id(id)
                .customerId("customer-1")
                .storeId("store-1")
                .cartId("cart-1")
                .status(OrderStatus.AWAITING_CUSTOMER_RESPONSE)
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
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }
}
