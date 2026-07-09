package com.sebet.order_service.order.service;

import com.sebet.order_service.cache.service.OrderLifecycleRedisUpdater;
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
import com.sebet.order_service.shared.exception.DriverNotAssignedException;
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
    private static final String ACTOR_DRIVER = "DRIVER";
    public static final String STORE_CANCEL_ORDER_ACTION = "STORE_CANCEL_ORDER";
    public static final String STORE_PROPOSE_CHANGES_ACTION = "STORE_PROPOSE_CHANGES";

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final OrderLifecycleRedisUpdater orderLifecycleRedisUpdater;
    private final OrderEventOutboxWriter orderEventOutboxWriter;
    private final OrderProposalRepository orderProposalRepository;
    private final OrderProposeChangesRedisUpdater orderProposeChangesRedisUpdater;

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

    @Transactional
    public OrderLifecycleResult storeCancel(
            String orderId,
            String storeId,
            OrderCancellationReason reason,
            String metadataJson
    ) {
        return storeCancel(orderId, storeId, reason, metadataJson, true);
    }

    @Transactional
    public OrderLifecycleResult storeCancelWithoutRedisUpdate(
            String orderId,
            String storeId,
            OrderCancellationReason reason,
            String metadataJson
    ) {
        return storeCancel(orderId, storeId, reason, metadataJson, false);
    }

    public void evictStoreCancelledRedisViews(String orderId, String storeId, String idempotencyKey) {
        UUID id = parseOrderId(orderId);
        OrderEntity order = orderRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        String changedAt = order.getCancelledAt() == null ? OffsetDateTime.now().toString() : order.getCancelledAt().toString();
        orderLifecycleRedisUpdater.applyTransition(
                order,
                OrderStatus.CANCELLED,
                changedAt,
                STORE_CANCEL_ORDER_ACTION,
                idempotencyKey
        );
    }

    @Transactional
    public OrderLifecycleResult storeProposeChangesWithoutRedisUpdate(
            String orderId,
            String storeId,
            String itemsJson,
            OffsetDateTime proposedAt
    ) {
        UUID id = parseOrderId(orderId);
        OrderEntity order = orderRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        OrderStatus previousStatus = order.getStatus();
        if (previousStatus != OrderStatus.CONFIRMED) {
            throw new OrderInvalidTransitionException(orderId, previousStatus, OrderStatus.AWAITING_CUSTOMER_RESPONSE);
        }

        order.setStatus(OrderStatus.AWAITING_CUSTOMER_RESPONSE);
        order.setUpdatedAt(proposedAt);

        OrderEntity savedOrder = orderRepository.saveAndFlush(order);
        orderStatusHistoryRepository.save(OrderStatusHistoryEntity.builder()
                .orderId(savedOrder.getId())
                .fromStatus(previousStatus)
                .toStatus(OrderStatus.AWAITING_CUSTOMER_RESPONSE)
                .changedByType(ACTOR_STORE)
                .changedById(storeId)
                .reason("STORE_PROPOSED_CHANGES")
                .createdAt(proposedAt)
                .build());
        orderProposalRepository.save(OrderProposalEntity.builder()
                .orderId(savedOrder.getId())
                .storeId(storeId)
                .proposedAt(proposedAt)
                .itemsJson(itemsJson)
                .build());
        orderEventOutboxWriter.saveOrderProposedToCustomer(savedOrder, itemsJson, proposedAt);

        return new OrderLifecycleResult(savedOrder, previousStatus, OrderStatus.AWAITING_CUSTOMER_RESPONSE, proposedAt);
    }

    @Transactional(readOnly = true)
    public void updateProposeChangesRedisViews(String orderId, String storeId, String idempotencyKey) {
        UUID id = parseOrderId(orderId);
        OrderEntity order = orderRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        OrderProposalEntity proposal = orderProposalRepository.findByOrderId(id)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        orderProposeChangesRedisUpdater.apply(order, proposal, idempotencyKey);
    }

    private OrderLifecycleResult storeCancel(
            String orderId,
            String storeId,
            OrderCancellationReason reason,
            String metadataJson,
            boolean updateRedis
    ) {
        UUID id = parseOrderId(orderId);
        OrderEntity order = orderRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        OrderStatus previousStatus = order.getStatus();
        if (previousStatus != OrderStatus.CONFIRMED
                && previousStatus != OrderStatus.AWAITING_CUSTOMER_RESPONSE) {
            throw new OrderInvalidTransitionException(orderId, previousStatus, OrderStatus.CANCELLED);
        }

        OffsetDateTime changedAt = OffsetDateTime.now();
        order.setStatus(OrderStatus.CANCELLED);
        order.setUpdatedAt(changedAt);
        order.setCancelledAt(changedAt);
        order.setCancelledBy(OrderCancelledBy.STORE);
        order.setCancellationReason(reason);

        OrderEntity savedOrder = orderRepository.saveAndFlush(order);
        orderStatusHistoryRepository.save(OrderStatusHistoryEntity.builder()
                .orderId(savedOrder.getId())
                .fromStatus(previousStatus)
                .toStatus(OrderStatus.CANCELLED)
                .changedByType(ACTOR_STORE)
                .changedById(storeId)
                .reason(reason.name())
                .metadataJson(metadataJson)
                .createdAt(changedAt)
                .build());

        orderEventOutboxWriter.saveOrderStatusTransition(
                savedOrder,
                previousStatus,
                OrderStatus.CANCELLED,
                changedAt,
                ACTOR_STORE,
                storeId,
                reason.name(),
                metadataJson
        );
        if (updateRedis) {
            registerRedisUpdate(savedOrder, OrderStatus.CANCELLED, changedAt, STORE_CANCEL_ORDER_ACTION);
        }
        return new OrderLifecycleResult(savedOrder, previousStatus, OrderStatus.CANCELLED, changedAt);
    }

    @Transactional
    public OrderLifecycleResult driverPickup(String orderId, String driverId) {
        return transitionDriverOrder(
                orderId, driverId,
                OrderStatus.READY_FOR_PICKUP, OrderStatus.OUT_FOR_DELIVERY,
                "DRIVER_PICKED_UP", null, null
        );
    }

    @Transactional
    public OrderLifecycleResult driverArrive(String orderId, String driverId, String metadataJson) {
        return transitionDriverOrder(
                orderId, driverId,
                OrderStatus.OUT_FOR_DELIVERY, OrderStatus.ARRIVED,
                "DRIVER_ARRIVED", metadataJson, null
        );
    }

    @Transactional
    public OrderLifecycleResult driverComplete(String orderId, String driverId) {
        return transitionDriverOrder(
                orderId, driverId,
                OrderStatus.ARRIVED, OrderStatus.DELIVERED,
                "DRIVER_COMPLETED", null, null
        );
    }

    private OrderLifecycleResult transitionDriverOrder(
            String orderId,
            String driverId,
            OrderStatus expectedStatus,
            OrderStatus targetStatus,
            String reason,
            String metadataJson,
            OrderMutation mutation
    ) {
        UUID id = parseOrderId(orderId);
        OrderEntity order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (!driverId.equals(order.getDriverId())) {
            throw new DriverNotAssignedException(orderId);
        }

        OrderStatus previousStatus = order.getStatus();
        if (previousStatus != expectedStatus) {
            throw new OrderInvalidTransitionException(orderId, previousStatus, targetStatus);
        }

        OffsetDateTime changedAt = OffsetDateTime.now();
        order.setStatus(targetStatus);
        order.setUpdatedAt(changedAt);
        if (targetStatus == OrderStatus.DELIVERED) {
            order.setDeliveredAt(changedAt);
        }
        if (mutation != null) {
            mutation.apply(order);
        }

        OrderEntity savedOrder = orderRepository.saveAndFlush(order);
        orderStatusHistoryRepository.save(OrderStatusHistoryEntity.builder()
                .orderId(savedOrder.getId())
                .fromStatus(previousStatus)
                .toStatus(targetStatus)
                .changedByType(ACTOR_DRIVER)
                .changedById(driverId)
                .reason(reason)
                .metadataJson(metadataJson)
                .createdAt(changedAt)
                .build());

        orderEventOutboxWriter.saveOrderStatusTransition(
                savedOrder,
                previousStatus,
                targetStatus,
                changedAt,
                ACTOR_DRIVER,
                driverId,
                reason,
                metadataJson
        );
        registerRedisUpdate(savedOrder, targetStatus, changedAt);
        return new OrderLifecycleResult(savedOrder, previousStatus, targetStatus, changedAt);
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

        orderEventOutboxWriter.saveOrderStatusTransition(
                savedOrder,
                previousStatus,
                targetStatus,
                changedAt,
                ACTOR_STORE,
                storeId,
                reason,
                metadataJson
        );
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

        registerAfterCommitOrRun(updateRedis);
    }

    private void registerRedisUpdate(
            OrderEntity order,
            OrderStatus targetStatus,
            OffsetDateTime changedAt,
            String sourceAction
    ) {
        Runnable updateRedis = () -> {
            try {
                orderLifecycleRedisUpdater.applyTransition(order, targetStatus, changedAt.toString(), sourceAction);
            } catch (RuntimeException exception) {
                log.error("Failed to update Redis views for order lifecycle transition orderId={}",
                        order.getId(), exception);
                throw exception;
            }
        };

        registerAfterCommitOrRun(updateRedis);
    }

    private void registerAfterCommitOrRun(Runnable updateRedis) {
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
