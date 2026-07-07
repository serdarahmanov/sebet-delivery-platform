package com.sebet.order_service.cache.service;

import com.sebet.order_service.cache.dto.OrderTimelineEntry;
import com.sebet.order_service.cache.repository.ActiveOrdersRedisRepository;
import com.sebet.order_service.cache.repository.OrderRedisRepository;
import com.sebet.order_service.cache.repository.OrderStatusRedisRepository;
import com.sebet.order_service.cache.repository.OrderTimelineRedisRepository;
import com.sebet.order_service.cache.repository.OrderTrackingRedisRepository;
import com.sebet.order_service.cache.repository.StoreActiveOrdersRedisRepository;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.shared.enums.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderLifecycleRedisUpdater {

    private final OrderStatusRedisRepository orderStatusRedisRepository;
    private final OrderTimelineRedisRepository orderTimelineRedisRepository;
    private final OrderRedisRepository orderRedisRepository;
    private final OrderTrackingRedisRepository orderTrackingRedisRepository;
    private final ActiveOrdersRedisRepository activeOrdersRedisRepository;
    private final StoreActiveOrdersRedisRepository storeActiveOrdersRedisRepository;

    public void applyTransition(OrderEntity order, OrderStatus newStatus, String changedAt) {
        String orderId = order.getId().toString();

        if (newStatus == OrderStatus.CANCELLED) {
            activeOrdersRedisRepository.remove(order.getCustomerId(), orderId);
            storeActiveOrdersRedisRepository.remove(order.getStoreId(), orderId);
            orderRedisRepository.delete(orderId);
            orderTrackingRedisRepository.delete(orderId);
            orderStatusRedisRepository.delete(orderId);
            orderTimelineRedisRepository.delete(orderId);
            return;
        }

        orderStatusRedisRepository.save(orderId, order.getCustomerId(), newStatus.name());

        String timelineStatus = toTimelineStatus(newStatus);
        if (timelineStatus != null && orderTimelineRedisRepository.findAll(orderId).stream()
                .noneMatch(entry -> timelineStatus.equals(entry.getStatus()))) {
            orderTimelineRedisRepository.append(orderId, new OrderTimelineEntry(timelineStatus, changedAt));
        }
    }

    private String toTimelineStatus(OrderStatus status) {
        return switch (status) {
            case READY_FOR_PICKUP -> "PACKED";
            default -> null;
        };
    }
}
