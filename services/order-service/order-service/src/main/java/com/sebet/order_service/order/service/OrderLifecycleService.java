package com.sebet.order_service.order.service;

import com.sebet.order_service.cache.service.OrderLifecycleRedisUpdater;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OrderStatusHistoryEntity;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.persistence.repository.OrderStatusHistoryRepository;
import com.sebet.order_service.shared.enums.OrderCancellationReason;
import com.sebet.order_service.shared.enums.OrderCancelledBy;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.exception.OrderInvalidTransitionException;
import com.sebet.order_service.shared.exception.OrderNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderLifecycleService {

    private static final String ACTOR_STORE = "STORE";

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final OrderLifecycleRedisUpdater orderLifecycleRedisUpdater;

    @Transactional
    public OrderLifecycleResult storeAccept(String orderId, String storeId) {
        return transitionStoreOrder(
                orderId,
                storeId,
                OrderStatus.PENDING,
                OrderStatus.CONFIRMED,
                "STORE_ACCEPTED",
                null,
                null
        );
    }

    @Transactional
    public OrderLifecycleResult storeReject(
            String orderId,
            String storeId,
            OrderCancellationReason reason
    ) {
        return storeReject(orderId, storeId, reason, null);
    }

    @Transactional
    public OrderLifecycleResult storeReject(
            String orderId,
            String storeId,
            OrderCancellationReason reason,
            String metadataJson
    ) {
        return transitionStoreOrder(
                orderId,
                storeId,
                OrderStatus.PENDING,
                OrderStatus.CANCELLED,
                reason.name(),
                metadataJson,
                cancellation -> {
                    cancellation.setCancelledBy(OrderCancelledBy.STORE);
                    cancellation.setCancellationReason(reason);
                }
        );
    }

    @Transactional
    public OrderLifecycleResult storeMarkReady(String orderId, String storeId) {
        return transitionStoreOrder(
                orderId,
                storeId,
                OrderStatus.CONFIRMED,
                OrderStatus.READY_FOR_PICKUP,
                "STORE_MARKED_READY",
                null,
                null
        );
    }

    private OrderLifecycleResult transitionStoreOrder(
            String orderId,
            String storeId,
            OrderStatus expectedStatus,
            OrderStatus targetStatus,
            String reason,
            String metadataJson,
            OrderMutation mutation
    ) {
        UUID id = parseOrderId(orderId);
        OrderEntity order = orderRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        OrderStatus previousStatus = order.getStatus();
        if (previousStatus != expectedStatus) {
            throw new OrderInvalidTransitionException(orderId, previousStatus, targetStatus);
        }

        OffsetDateTime changedAt = OffsetDateTime.now();
        order.setStatus(targetStatus);
        order.setUpdatedAt(changedAt);
        if (targetStatus == OrderStatus.CANCELLED) {
            order.setCancelledAt(changedAt);
        }
        if (mutation != null) {
            mutation.apply(order);
        }

        OrderEntity savedOrder = orderRepository.saveAndFlush(order);
        orderStatusHistoryRepository.save(OrderStatusHistoryEntity.builder()
                .orderId(savedOrder.getId())
                .fromStatus(previousStatus)
                .toStatus(targetStatus)
                .changedByType(ACTOR_STORE)
                .changedById(storeId)
                .reason(reason)
                .metadataJson(metadataJson)
                .createdAt(changedAt)
                .build());

        registerRedisUpdate(savedOrder, targetStatus, changedAt);
        return new OrderLifecycleResult(savedOrder, previousStatus, targetStatus, changedAt);
    }

    private void registerRedisUpdate(OrderEntity order, OrderStatus targetStatus, OffsetDateTime changedAt) {
        Runnable updateRedis = () -> {
            try {
                orderLifecycleRedisUpdater.applyTransition(order, targetStatus, changedAt.toString());
            } catch (RuntimeException exception) {
                log.error("Failed to update Redis views for order lifecycle transition orderId={}",
                        order.getId(), exception);
                throw exception;
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    updateRedis.run();
                }
            });
            return;
        }

        updateRedis.run();
    }

    private UUID parseOrderId(String orderId) {
        try {
            return UUID.fromString(orderId);
        } catch (IllegalArgumentException exception) {
            throw new OrderNotFoundException(orderId);
        }
    }

    @FunctionalInterface
    private interface OrderMutation {
        void apply(OrderEntity order);
    }
}
