package com.sebet.order_service.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.cache.dto.OrderProposalsCacheDto;
import com.sebet.order_service.cache.service.OrderLifecycleRedisUpdater;
import com.sebet.order_service.cache.service.OrderCancelActiveProposalRedisUpdater;
import com.sebet.order_service.cache.service.OrderProposeChangesRedisUpdater;
import com.sebet.order_service.order.event.OrderEventOutboxWriter;
import com.sebet.order_service.order.event.OrderProposalAcceptedEventData;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OrderItemEntity;
import com.sebet.order_service.persistence.entity.OrderProposalEntity;
import com.sebet.order_service.persistence.entity.OrderStatusHistoryEntity;
import com.sebet.order_service.persistence.repository.OrderItemRepository;
import com.sebet.order_service.persistence.repository.OrderProposalRepository;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.persistence.repository.OrderStatusHistoryRepository;
import com.sebet.order_service.shared.enums.OrderCancellationReason;
import com.sebet.order_service.shared.enums.OrderCancelledBy;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ProposalStatus;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderLifecycleService {

    private static final String ACTOR_STORE = "STORE";
    private static final String ACTOR_DRIVER = "DRIVER";
    private static final String ACTOR_SYSTEM = "SYSTEM";
    private static final String ACTOR_USER = "USER";
    public static final String STORE_CANCEL_ORDER_ACTION = "STORE_CANCEL_ORDER";
    public static final String STORE_PROPOSE_CHANGES_ACTION = "STORE_PROPOSE_CHANGES";
    public static final String INTERNAL_ACTIVATE_SCHEDULED_ACTION = "INTERNAL_ACTIVATE_SCHEDULED";
    public static final String INTERNAL_SYSTEM_CANCEL_ACTION = "INTERNAL_SYSTEM_CANCEL";
    public static final String INTERNAL_ADMIN_CANCEL_ACTION = "INTERNAL_ADMIN_CANCEL";
    public static final String INTERNAL_CANCEL_ACTIVE_PROPOSAL_ACTION = "INTERNAL_CANCEL_ACTIVE_PROPOSAL";
    public static final String INTERNAL_CANCEL_PROPOSAL_AND_ORDER_ACTION = "INTERNAL_CANCEL_PROPOSAL_AND_ORDER";
    public static final String CUSTOMER_CANCEL_ORDER_ACTION = "CUSTOMER_CANCEL_ORDER";
    public static final String CUSTOMER_RESPOND_ACCEPT_ACTION = "CUSTOMER_RESPOND_ACCEPT";

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final OrderLifecycleRedisUpdater orderLifecycleRedisUpdater;
    private final OrderEventOutboxWriter orderEventOutboxWriter;
    private final OrderProposalRepository orderProposalRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderProposeChangesRedisUpdater orderProposeChangesRedisUpdater;
    private final OrderCancelActiveProposalRedisUpdater orderCancelActiveProposalRedisUpdater;
    private final ObjectMapper objectMapper;

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
    public OrderLifecycleResult activateScheduled(String orderId) {
        OrderLifecycleResult result = activateScheduledInTransaction(orderId);
        registerRedisUpdate(
                result.order(),
                OrderStatus.PENDING,
                result.changedAt(),
                INTERNAL_ACTIVATE_SCHEDULED_ACTION
        );
        return result;
    }

    @Transactional
    public OrderLifecycleResult activateScheduledWithoutRedisUpdate(String orderId) {
        return activateScheduledInTransaction(orderId);
    }

    public void updateScheduledActivationRedisViews(
            OrderEntity order,
            OffsetDateTime changedAt,
            String idempotencyKey
    ) {
        orderLifecycleRedisUpdater.applyTransition(
                order,
                OrderStatus.PENDING,
                changedAt.toString(),
                INTERNAL_ACTIVATE_SCHEDULED_ACTION,
                idempotencyKey
        );
    }

    @Transactional(readOnly = true)
    public void updateScheduledActivationRedisViews(String orderId, String idempotencyKey) {
        UUID id = parseOrderId(orderId);
        OrderEntity order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        OffsetDateTime changedAt = order.getUpdatedAt() == null ? OffsetDateTime.now() : order.getUpdatedAt();
        updateScheduledActivationRedisViews(order, changedAt, idempotencyKey);
    }

    @Transactional
    public OrderLifecycleResult systemCancelWithoutRedisUpdate(
            String orderId,
            OrderCancellationReason reason,
            String metadataJson
    ) {
        return cancelOrderWithoutRedisUpdate(orderId, reason, metadataJson, true);
    }

    @Transactional
    public OrderLifecycleResult adminCancelWithoutRedisUpdate(
            String orderId,
            OrderCancellationReason reason,
            String metadataJson
    ) {
        return cancelOrderWithoutRedisUpdate(orderId, reason, metadataJson, false);
    }

    private OrderLifecycleResult cancelOrderWithoutRedisUpdate(
            String orderId,
            OrderCancellationReason reason,
            String metadataJson,
            boolean enforceSystemFlowGuard
    ) {
        UUID id = parseOrderId(orderId);
        OrderEntity order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        OrderStatus previousStatus = order.getStatus();
        if (previousStatus == OrderStatus.DELIVERED
                || previousStatus == OrderStatus.CANCELLED
                || (enforceSystemFlowGuard && (previousStatus == OrderStatus.OUT_FOR_DELIVERY
                || previousStatus == OrderStatus.ARRIVED))) {
            throw new OrderInvalidTransitionException(orderId, previousStatus, OrderStatus.CANCELLED);
        }

        OffsetDateTime changedAt = OffsetDateTime.now();
        order.setStatus(OrderStatus.CANCELLED);
        order.setUpdatedAt(changedAt);
        order.setCancelledAt(changedAt);
        order.setCancelledBy(OrderCancelledBy.SYSTEM);
        order.setCancellationReason(reason);

        OrderEntity savedOrder = orderRepository.saveAndFlush(order);

        if (previousStatus == OrderStatus.AWAITING_CUSTOMER_RESPONSE) {
            orderProposalRepository.findByOrderId(id).ifPresent(proposal -> {
                proposal.setStatus(ProposalStatus.SYSTEM_CANCELLED);
                orderProposalRepository.saveAndFlush(proposal);
            });
        }

        orderStatusHistoryRepository.save(OrderStatusHistoryEntity.builder()
                .orderId(savedOrder.getId())
                .fromStatus(previousStatus)
                .toStatus(OrderStatus.CANCELLED)
                .changedByType(ACTOR_SYSTEM)
                .reason(reason.name())
                .metadataJson(metadataJson)
                .createdAt(changedAt)
                .build());

        orderEventOutboxWriter.saveOrderStatusTransition(
                savedOrder,
                previousStatus,
                OrderStatus.CANCELLED,
                changedAt,
                ACTOR_SYSTEM,
                null,
                reason.name(),
                metadataJson
        );
        return new OrderLifecycleResult(savedOrder, previousStatus, OrderStatus.CANCELLED, changedAt);
    }

    public void evictSystemCancelledRedisViews(String orderId, String idempotencyKey) {
        evictCancelledRedisViews(orderId, INTERNAL_SYSTEM_CANCEL_ACTION, idempotencyKey);
    }

    public void evictAdminCancelledRedisViews(String orderId, String idempotencyKey) {
        evictCancelledRedisViews(orderId, INTERNAL_ADMIN_CANCEL_ACTION, idempotencyKey);
    }

    private void evictCancelledRedisViews(String orderId, String sourceAction, String idempotencyKey) {
        UUID id = parseOrderId(orderId);
        OrderEntity order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        OffsetDateTime changedAt = order.getCancelledAt() == null ? OffsetDateTime.now() : order.getCancelledAt();
        evictCancelledRedisViews(order, changedAt, sourceAction, idempotencyKey);
    }

    public void evictSystemCancelledRedisViews(
            OrderEntity order,
            OffsetDateTime changedAt,
            String idempotencyKey
    ) {
        evictCancelledRedisViews(order, changedAt, INTERNAL_SYSTEM_CANCEL_ACTION, idempotencyKey);
    }

    public void evictAdminCancelledRedisViews(
            OrderEntity order,
            OffsetDateTime changedAt,
            String idempotencyKey
    ) {
        evictCancelledRedisViews(order, changedAt, INTERNAL_ADMIN_CANCEL_ACTION, idempotencyKey);
    }

    private void evictCancelledRedisViews(
            OrderEntity order,
            OffsetDateTime changedAt,
            String sourceAction,
            String idempotencyKey
    ) {
        orderLifecycleRedisUpdater.applyTransition(
                order,
                OrderStatus.CANCELLED,
                changedAt.toString(),
                sourceAction,
                idempotencyKey
        );
    }

    private OrderLifecycleResult activateScheduledInTransaction(String orderId) {
        UUID id = parseOrderId(orderId);
        OrderEntity order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        OrderStatus previousStatus = order.getStatus();
        if (previousStatus != OrderStatus.SCHEDULED) {
            throw new OrderInvalidTransitionException(orderId, previousStatus, OrderStatus.PENDING);
        }

        OffsetDateTime changedAt = OffsetDateTime.now();
        order.setStatus(OrderStatus.PENDING);
        order.setUpdatedAt(changedAt);

        OrderEntity savedOrder = orderRepository.saveAndFlush(order);
        orderStatusHistoryRepository.save(OrderStatusHistoryEntity.builder()
                .orderId(savedOrder.getId())
                .fromStatus(previousStatus)
                .toStatus(OrderStatus.PENDING)
                .changedByType(ACTOR_SYSTEM)
                .reason("SCHEDULED_ACTIVATED")
                .createdAt(changedAt)
                .build());

        orderEventOutboxWriter.saveOrderStatusTransition(
                savedOrder,
                previousStatus,
                OrderStatus.PENDING,
                changedAt,
                ACTOR_SYSTEM,
                null,
                "SCHEDULED_ACTIVATED",
                null
        );
        return new OrderLifecycleResult(savedOrder, previousStatus, OrderStatus.PENDING, changedAt);
    }

    @Transactional
    public OrderLifecycleResult customerCancelWithoutRedisUpdate(String orderId, String userId) {
        UUID id = parseOrderId(orderId);
        OrderEntity order = orderRepository.findByIdAndCustomerId(id, userId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        OrderStatus previousStatus = order.getStatus();
        if (previousStatus != OrderStatus.PENDING
                && previousStatus != OrderStatus.CONFIRMED
                && previousStatus != OrderStatus.SCHEDULED) {
            throw new OrderInvalidTransitionException(orderId, previousStatus, OrderStatus.CANCELLED);
        }

        OffsetDateTime changedAt = OffsetDateTime.now();
        order.setStatus(OrderStatus.CANCELLED);
        order.setUpdatedAt(changedAt);
        order.setCancelledAt(changedAt);
        order.setCancelledBy(OrderCancelledBy.USER);
        order.setCancellationReason(OrderCancellationReason.USER_REQUESTED);

        OrderEntity savedOrder = orderRepository.saveAndFlush(order);
        orderStatusHistoryRepository.save(OrderStatusHistoryEntity.builder()
                .orderId(savedOrder.getId())
                .fromStatus(previousStatus)
                .toStatus(OrderStatus.CANCELLED)
                .changedByType(ACTOR_USER)
                .changedById(userId)
                .reason(OrderCancellationReason.USER_REQUESTED.name())
                .createdAt(changedAt)
                .build());

        orderEventOutboxWriter.saveOrderStatusTransition(
                savedOrder,
                previousStatus,
                OrderStatus.CANCELLED,
                changedAt,
                ACTOR_USER,
                userId,
                OrderCancellationReason.USER_REQUESTED.name(),
                null
        );
        return new OrderLifecycleResult(savedOrder, previousStatus, OrderStatus.CANCELLED, changedAt);
    }

    public void evictCustomerCancelledRedisViews(OrderEntity order, OffsetDateTime changedAt, String idempotencyKey) {
        evictCancelledRedisViews(order, changedAt, CUSTOMER_CANCEL_ORDER_ACTION, idempotencyKey);
    }

    @Transactional
    public OrderLifecycleResult customerRespondCancelWithoutRedisUpdate(String orderId, String userId) {
        UUID id = parseOrderId(orderId);
        OrderEntity order = orderRepository.findByIdAndCustomerId(id, userId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        OrderStatus previousStatus = order.getStatus();
        if (previousStatus != OrderStatus.AWAITING_CUSTOMER_RESPONSE) {
            throw new OrderInvalidTransitionException(orderId, previousStatus, OrderStatus.CANCELLED);
        }

        OrderProposalEntity proposal = orderProposalRepository.findByOrderIdAndStatus(id, ProposalStatus.ACTIVE)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        OffsetDateTime changedAt = OffsetDateTime.now();
        proposal.setGlobalDecision("CANCEL_ORDER");
        proposal.setRespondedAt(changedAt);
        proposal.setStatus(ProposalStatus.REJECTED);
        orderProposalRepository.saveAndFlush(proposal);

        order.setStatus(OrderStatus.CANCELLED);
        order.setUpdatedAt(changedAt);
        order.setCancelledAt(changedAt);
        order.setCancelledBy(OrderCancelledBy.USER);
        order.setCancellationReason(OrderCancellationReason.USER_REQUESTED);

        OrderEntity savedOrder = orderRepository.saveAndFlush(order);
        orderStatusHistoryRepository.save(OrderStatusHistoryEntity.builder()
                .orderId(savedOrder.getId())
                .fromStatus(previousStatus)
                .toStatus(OrderStatus.CANCELLED)
                .changedByType(ACTOR_USER)
                .changedById(userId)
                .reason(OrderCancellationReason.USER_REQUESTED.name())
                .createdAt(changedAt)
                .build());

        orderEventOutboxWriter.saveOrderStatusTransition(
                savedOrder,
                previousStatus,
                OrderStatus.CANCELLED,
                changedAt,
                ACTOR_USER,
                userId,
                OrderCancellationReason.USER_REQUESTED.name(),
                null
        );
        return new OrderLifecycleResult(savedOrder, previousStatus, OrderStatus.CANCELLED, changedAt);
    }

    @Transactional
    public OrderLifecycleResult customerRespondAcceptWithoutRedisUpdate(
            String orderId,
            String userId,
            String globalDecision,
            List<OrderProposalAcceptedEventData.ItemDecisionData> itemDecisions
    ) {
        UUID id = parseOrderId(orderId);
        OrderEntity order = orderRepository.findByIdAndCustomerId(id, userId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() != OrderStatus.AWAITING_CUSTOMER_RESPONSE) {
            throw new OrderInvalidTransitionException(orderId, order.getStatus(), "RESPOND_TO_PROPOSAL");
        }

        OrderProposalEntity proposal = orderProposalRepository.findByOrderIdAndStatus(id, ProposalStatus.ACTIVE)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (itemDecisions != null) {
            validateItemDecisions(orderId, itemDecisions, proposal.getItemsJson());
        }

        OffsetDateTime respondedAt = OffsetDateTime.now();
        proposal.setGlobalDecision(globalDecision);
        proposal.setRespondedAt(respondedAt);
        proposal.setStatus(ProposalStatus.ACCEPTED);
        if (itemDecisions != null && !itemDecisions.isEmpty()) {
            try {
                proposal.setItemDecisionsJson(objectMapper.writeValueAsString(itemDecisions));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to serialize item decisions for order " + orderId, e);
            }
        }
        orderProposalRepository.saveAndFlush(proposal);

        List<OrderItemEntity> orderItems = orderItemRepository.findByOrderIdOrderByLineNumberAsc(id);
        orderEventOutboxWriter.saveOrderProposalAccepted(order, proposal, orderItems, globalDecision, itemDecisions, respondedAt);

        return new OrderLifecycleResult(order, OrderStatus.AWAITING_CUSTOMER_RESPONSE, OrderStatus.AWAITING_CUSTOMER_RESPONSE, respondedAt);
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

    @Transactional
    public OrderLifecycleResult cancelActiveProposalWithoutRedisUpdate(String orderId) {
        UUID id = parseOrderId(orderId);
        OrderEntity order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        OrderStatus previousStatus = order.getStatus();
        if (previousStatus != OrderStatus.AWAITING_CUSTOMER_RESPONSE) {
            throw new OrderInvalidTransitionException(orderId, previousStatus, OrderStatus.CONFIRMED);
        }

        OrderProposalEntity proposal = orderProposalRepository.findByOrderId(id)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        OffsetDateTime changedAt = OffsetDateTime.now();
        order.setStatus(OrderStatus.CONFIRMED);
        order.setUpdatedAt(changedAt);

        OrderEntity savedOrder = orderRepository.saveAndFlush(order);
        proposal.setStatus(ProposalStatus.CANCELLED);
        orderProposalRepository.saveAndFlush(proposal);
        orderStatusHistoryRepository.save(OrderStatusHistoryEntity.builder()
                .orderId(savedOrder.getId())
                .fromStatus(previousStatus)
                .toStatus(OrderStatus.CONFIRMED)
                .changedByType(ACTOR_SYSTEM)
                .reason("ACTIVE_PROPOSAL_CANCELLED")
                .createdAt(changedAt)
                .build());

        orderEventOutboxWriter.saveOrderStatusTransition(
                savedOrder,
                previousStatus,
                OrderStatus.CONFIRMED,
                changedAt,
                ACTOR_SYSTEM,
                null,
                "ACTIVE_PROPOSAL_CANCELLED",
                null
        );
        return new OrderLifecycleResult(savedOrder, previousStatus, OrderStatus.CONFIRMED, changedAt);
    }

    @Transactional
    public OrderLifecycleResult cancelProposalAndOrderWithoutRedisUpdate(String orderId) {
        UUID id = parseOrderId(orderId);
        OrderEntity order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        OrderStatus previousStatus = order.getStatus();
        if (previousStatus != OrderStatus.AWAITING_CUSTOMER_RESPONSE) {
            throw new OrderInvalidTransitionException(orderId, previousStatus, OrderStatus.CANCELLED);
        }

        OrderProposalEntity proposal = orderProposalRepository.findByOrderId(id)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        OffsetDateTime changedAt = OffsetDateTime.now();
        order.setStatus(OrderStatus.CANCELLED);
        order.setUpdatedAt(changedAt);
        order.setCancelledAt(changedAt);
        order.setCancelledBy(OrderCancelledBy.SYSTEM);
        order.setCancellationReason(OrderCancellationReason.AWAITING_CUSTOMER_RESPONSE_TIMEOUT);

        OrderEntity savedOrder = orderRepository.saveAndFlush(order);
        proposal.setStatus(ProposalStatus.TIMED_OUT);
        orderProposalRepository.saveAndFlush(proposal);
        orderStatusHistoryRepository.save(OrderStatusHistoryEntity.builder()
                .orderId(savedOrder.getId())
                .fromStatus(previousStatus)
                .toStatus(OrderStatus.CANCELLED)
                .changedByType(ACTOR_SYSTEM)
                .reason(OrderCancellationReason.AWAITING_CUSTOMER_RESPONSE_TIMEOUT.name())
                .createdAt(changedAt)
                .build());

        orderEventOutboxWriter.saveOrderStatusTransition(
                savedOrder,
                previousStatus,
                OrderStatus.CANCELLED,
                changedAt,
                ACTOR_SYSTEM,
                null,
                OrderCancellationReason.AWAITING_CUSTOMER_RESPONSE_TIMEOUT.name(),
                null
        );
        return new OrderLifecycleResult(savedOrder, previousStatus, OrderStatus.CANCELLED, changedAt);
    }

    public void updateCancelActiveProposalRedisViews(
            OrderEntity order,
            String idempotencyKey
    ) {
        orderCancelActiveProposalRedisUpdater.apply(order, idempotencyKey);
    }

    @Transactional(readOnly = true)
    public void updateCancelActiveProposalRedisViews(String orderId, String idempotencyKey) {
        UUID id = parseOrderId(orderId);
        OrderEntity order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        orderCancelActiveProposalRedisUpdater.apply(order, idempotencyKey);
    }

    public void evictCancelProposalAndOrderRedisViews(
            OrderEntity order,
            OffsetDateTime changedAt,
            String idempotencyKey
    ) {
        evictCancelledRedisViews(order, changedAt, INTERNAL_CANCEL_PROPOSAL_AND_ORDER_ACTION, idempotencyKey);
    }

    public void evictCancelProposalAndOrderRedisViews(String orderId, String idempotencyKey) {
        evictCancelledRedisViews(orderId, INTERNAL_CANCEL_PROPOSAL_AND_ORDER_ACTION, idempotencyKey);
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

        if (previousStatus == OrderStatus.AWAITING_CUSTOMER_RESPONSE) {
            orderProposalRepository.findByOrderId(id).ifPresent(proposal -> {
                proposal.setStatus(ProposalStatus.STORE_CANCELLED);
                orderProposalRepository.saveAndFlush(proposal);
            });
        }

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

    private void validateItemDecisions(
            String orderId,
            List<OrderProposalAcceptedEventData.ItemDecisionData> itemDecisions,
            String proposalItemsJson
    ) {
        List<OrderProposalsCacheDto.ProposedItem> proposedItems;
        try {
            proposedItems = objectMapper.readValue(
                    proposalItemsJson,
                    objectMapper.getTypeFactory().constructCollectionType(
                            List.class, OrderProposalsCacheDto.ProposedItem.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse proposal items for order " + orderId, e);
        }

        // 1. No duplicate productIds in the customer's decision list
        Set<String> seen = new HashSet<>();
        for (OrderProposalAcceptedEventData.ItemDecisionData d : itemDecisions) {
            if (!seen.add(d.productId())) {
                throw new IllegalArgumentException(
                        "Duplicate productId in itemDecisions: " + d.productId());
            }
        }

        Map<String, OrderProposalsCacheDto.ProposedItem> proposedByProductId = proposedItems.stream()
                .collect(Collectors.toMap(
                        OrderProposalsCacheDto.ProposedItem::getProductId,
                        Function.identity()));

        // 2. Every decision must reference a product that is in the proposal
        for (OrderProposalAcceptedEventData.ItemDecisionData d : itemDecisions) {
            if (!proposedByProductId.containsKey(d.productId())) {
                throw new IllegalArgumentException(
                        "productId not in proposal: " + d.productId());
            }
        }

        // 3. Every proposed item must have exactly one decision
        Set<String> decidedIds = itemDecisions.stream()
                .map(OrderProposalAcceptedEventData.ItemDecisionData::productId)
                .collect(Collectors.toSet());
        for (OrderProposalsCacheDto.ProposedItem proposed : proposedItems) {
            if (!decidedIds.contains(proposed.getProductId())) {
                throw new IllegalArgumentException(
                        "Missing decision for proposed item: " + proposed.getProductId());
            }
        }

        // 4. Per-item action constraints
        for (OrderProposalAcceptedEventData.ItemDecisionData d : itemDecisions) {
            OrderProposalsCacheDto.ProposedItem proposed = proposedByProductId.get(d.productId());
            boolean fullyOutOfStock = proposed.getAvailableQuantity() == null;

            switch (d.action()) {
                case "ACCEPT_PROPOSED_QUANTITY" -> {
                    if (fullyOutOfStock) {
                        throw new IllegalArgumentException(
                                "Cannot ACCEPT_PROPOSED_QUANTITY for fully out-of-stock item: "
                                        + d.productId());
                    }
                }
                case "REQUEST_CUSTOM_QUANTITY" -> {
                    if (fullyOutOfStock) {
                        throw new IllegalArgumentException(
                                "Cannot REQUEST_CUSTOM_QUANTITY for fully out-of-stock item: "
                                        + d.productId());
                    }
                    if (d.customQuantity() == null) {
                        throw new IllegalArgumentException(
                                "customQuantity is required for REQUEST_CUSTOM_QUANTITY on product: "
                                        + d.productId());
                    }
                    if (d.customQuantity().compareTo(proposed.getAvailableQuantity()) > 0) {
                        throw new IllegalArgumentException(
                                "customQuantity " + d.customQuantity()
                                        + " exceeds availableQuantity " + proposed.getAvailableQuantity()
                                        + " for product: " + d.productId());
                    }
                }
                // REMOVE_ITEM is always valid regardless of stock state
            }
        }
    }

    @FunctionalInterface
    private interface OrderMutation {
        void apply(OrderEntity order);
    }
}
