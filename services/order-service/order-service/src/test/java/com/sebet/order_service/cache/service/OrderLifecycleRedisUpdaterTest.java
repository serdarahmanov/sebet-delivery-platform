package com.sebet.order_service.cache.service;

import com.sebet.order_service.cache.dto.OrderTimelineEntry;
import com.sebet.order_service.cache.repository.ActiveOrdersRedisRepository;
import com.sebet.order_service.cache.repository.OrderRedisRepository;
import com.sebet.order_service.cache.repository.OrderStatusRedisRepository;
import com.sebet.order_service.cache.repository.OrderTimelineRedisRepository;
import com.sebet.order_service.cache.repository.OrderTrackingRedisRepository;
import com.sebet.order_service.cache.repository.StoreActiveOrdersRedisRepository;
import com.sebet.order_service.cache.repository.VerificationCodeRedisRepository;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ScheduleType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderLifecycleRedisUpdaterTest {

    private final OrderStatusRedisRepository orderStatusRedisRepository = mock(OrderStatusRedisRepository.class);
    private final OrderTimelineRedisRepository orderTimelineRedisRepository = mock(OrderTimelineRedisRepository.class);
    private final OrderRedisRepository orderRedisRepository = mock(OrderRedisRepository.class);
    private final OrderTrackingRedisRepository orderTrackingRedisRepository = mock(OrderTrackingRedisRepository.class);
    private final ActiveOrdersRedisRepository activeOrdersRedisRepository = mock(ActiveOrdersRedisRepository.class);
    private final StoreActiveOrdersRedisRepository storeActiveOrdersRedisRepository = mock(StoreActiveOrdersRedisRepository.class);
    private final VerificationCodeRedisRepository verificationCodeRedisRepository = mock(VerificationCodeRedisRepository.class);
    private final OrderCacheEvictionService orderCacheEvictionService = mock(OrderCacheEvictionService.class);

    private final OrderLifecycleRedisUpdater updater = new OrderLifecycleRedisUpdater(
            orderStatusRedisRepository,
            orderTimelineRedisRepository,
            orderRedisRepository,
            orderTrackingRedisRepository,
            activeOrdersRedisRepository,
            storeActiveOrdersRedisRepository,
            verificationCodeRedisRepository,
            orderCacheEvictionService
    );

    @Test
    void confirmedTransitionUpdatesStatusOnly() {
        OrderEntity order = order();

        updater.applyTransition(order, OrderStatus.CONFIRMED, "2026-07-07T10:00:00Z");

        verify(orderStatusRedisRepository).save(order.getId().toString(), "customer-1", "store-1", "CONFIRMED");
        verify(orderTimelineRedisRepository, never()).append(any(), any());
        verify(orderRedisRepository, never()).delete(any());
    }

    @Test
    void readyTransitionUpdatesStatusAndAppendsPackedTimelineWhenMissing() {
        OrderEntity order = order();
        when(orderTimelineRedisRepository.findAll(order.getId().toString())).thenReturn(List.of(
                new OrderTimelineEntry("PLACED", "2026-07-07T09:50:00Z")
        ));

        updater.applyTransition(order, OrderStatus.READY_FOR_PICKUP, "2026-07-07T10:00:00Z");

        verify(orderStatusRedisRepository).save(order.getId().toString(), "customer-1", "store-1", "READY_FOR_PICKUP");
        verify(orderTimelineRedisRepository).append(
                order.getId().toString(),
                new OrderTimelineEntry("PACKED", "2026-07-07T10:00:00Z")
        );
    }

    @Test
    void readyTransitionDoesNotAppendDuplicatePackedTimeline() {
        OrderEntity order = order();
        when(orderTimelineRedisRepository.findAll(order.getId().toString())).thenReturn(List.of(
                new OrderTimelineEntry("PACKED", "2026-07-07T09:50:00Z")
        ));

        updater.applyTransition(order, OrderStatus.READY_FOR_PICKUP, "2026-07-07T10:00:00Z");

        verify(orderTimelineRedisRepository, never()).append(any(), any());
    }

    @Test
    void cancelledTransitionRemovesHotViews() {
        OrderEntity order = order();
        String orderId = order.getId().toString();

        updater.applyTransition(order, OrderStatus.CANCELLED, "2026-07-07T10:00:00Z");

        verify(activeOrdersRedisRepository).remove("customer-1", orderId);
        verify(storeActiveOrdersRedisRepository).remove("store-1", orderId);
        verify(orderRedisRepository).delete(orderId);
        verify(orderTrackingRedisRepository).delete(orderId);
        verify(orderStatusRedisRepository).delete(orderId);
        verify(orderTimelineRedisRepository).delete(orderId);
        verify(orderCacheEvictionService, never()).evictCancelledOrderHotViewsOrRequestEviction(any(), any(), any());
    }

    @Test
    void cancelledTransitionUsesFallbackPatternForC2WhenSourceActionIsProvided() {
        OrderEntity order = order();
        String orderId = order.getId().toString();

        updater.applyTransition(order, OrderStatus.CANCELLED, "2026-07-07T10:00:00Z", "STORE_CANCEL_ORDER", "idem-1");

        verify(orderCacheEvictionService).evictCancelledOrderHotViewsOrRequestEviction(
                orderId,
                "STORE_CANCEL_ORDER",
                "idem-1"
        );
        verify(orderRedisRepository, never()).delete(any());
        verify(activeOrdersRedisRepository, never()).remove(any(), any());
        verify(storeActiveOrdersRedisRepository, never()).remove(any(), any());
        verify(orderTrackingRedisRepository, never()).delete(any());
        verify(orderStatusRedisRepository, never()).delete(any());
        verify(orderTimelineRedisRepository, never()).delete(any());
    }

    @Test
    void deliveredTransitionAppendsDeliveredTimelineAndClearsHotViews() {
        OrderEntity order = order();
        String orderId = order.getId().toString();

        updater.applyTransition(order, OrderStatus.DELIVERED, "2026-07-07T10:15:00Z");

        verify(activeOrdersRedisRepository).remove("customer-1", orderId);
        verify(storeActiveOrdersRedisRepository).remove("store-1", orderId);
        verify(orderRedisRepository).delete(orderId);
        verify(orderTrackingRedisRepository).delete(orderId);
        verify(verificationCodeRedisRepository).delete(orderId);
        verify(orderStatusRedisRepository).save(orderId, "customer-1", "store-1", "DELIVERED");
        verify(orderTimelineRedisRepository).append(
                orderId,
                new OrderTimelineEntry("DELIVERED", "2026-07-07T10:15:00Z")
        );
    }

    private OrderEntity order() {
        OffsetDateTime now = OffsetDateTime.now();
        return OrderEntity.builder()
                .id(UUID.randomUUID())
                .customerId("customer-1")
                .storeId("store-1")
                .cartId("cart-1")
                .status(OrderStatus.PENDING)
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
