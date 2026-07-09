package com.sebet.order_service.store.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.cache.dto.OrderProposalsCacheDto;
import com.sebet.order_service.order.service.OrderLifecycleResult;
import com.sebet.order_service.order.service.OrderLifecycleService;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OrderItemEntity;
import com.sebet.order_service.persistence.repository.OrderItemRepository;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.shared.enums.OrderCancellationReason;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.exception.OrderInvalidTransitionException;
import com.sebet.order_service.shared.exception.OrderNotFoundException;
import com.sebet.order_service.shared.idempotency.IdempotentCommandService;
import com.sebet.order_service.store.dto.request.ProposeOrderChangesRequest;
import com.sebet.order_service.store.dto.request.RejectOrderRequest;
import com.sebet.order_service.store.dto.request.StoreCancelOrderRequest;
import com.sebet.order_service.store.dto.response.StoreAcceptOrderResponse;
import com.sebet.order_service.store.dto.response.StoreCancelOrderResponse;
import com.sebet.order_service.store.dto.response.StoreProposeOrderChangesResponse;
import com.sebet.order_service.store.dto.response.StoreReadyOrderResponse;
import com.sebet.order_service.store.dto.response.StoreRejectOrderResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StoreOrderLifecycleService {

    private static final String STORE_CANCEL_ORDER_ACTION = OrderLifecycleService.STORE_CANCEL_ORDER_ACTION;
    private static final String STORE_PROPOSE_CHANGES_ACTION = OrderLifecycleService.STORE_PROPOSE_CHANGES_ACTION;

    private final OrderLifecycleService orderLifecycleService;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ObjectMapper objectMapper;
    private final IdempotentCommandService idempotentCommandService;

    public StoreAcceptOrderResponse acceptOrder(String storeId, String orderId) {
        OrderLifecycleResult result = orderLifecycleService.storeAccept(orderId, storeId);
        return new StoreAcceptOrderResponse(
                orderId,
                result.newStatus(),
                result.changedAt().toString()
        );
    }

    public StoreRejectOrderResponse rejectOrder(String storeId, String orderId, RejectOrderRequest request) {
        validateRejectRequest(request);
        OrderEntity order = loadPendingStoreOrder(orderId, storeId);
        String metadataJson = rejectionMetadata(order, request);
        OrderCancellationReason reason = toCancellationReason(request.reason());
        OrderLifecycleResult result = orderLifecycleService.storeReject(orderId, storeId, reason, metadataJson);
        return new StoreRejectOrderResponse(
                orderId,
                result.newStatus(),
                reason,
                result.changedAt().toString()
        );
    }

    public StoreReadyOrderResponse markOrderReady(String storeId, String orderId) {
        OrderLifecycleResult result = orderLifecycleService.storeMarkReady(orderId, storeId);
        return new StoreReadyOrderResponse(
                orderId,
                result.newStatus(),
                result.changedAt().toString()
        );
    }

    public StoreCancelOrderResponse cancelOrder(
            String storeId,
            String orderId,
            StoreCancelOrderRequest request,
            String idempotencyKey
    ) {
        validateCancelRequest(request);
        OrderCancellationReason reason = toCancellationReason(request.reason());
        StoreCancelOrderResponse response = idempotentCommandService.execute(
                STORE_CANCEL_ORDER_ACTION,
                idempotencyKey,
                orderId,
                cancelRequestFingerprint(storeId, orderId, request),
                StoreCancelOrderResponse.class,
                () -> {
                    OrderLifecycleResult result = orderLifecycleService.storeCancelWithoutRedisUpdate(
                            orderId,
                            storeId,
                            reason,
                            writeMetadata(new StoreCancelMetadata(request.note()))
                    );
                    return new StoreCancelOrderResponse(
                            orderId,
                            result.newStatus(),
                            reason,
                            result.changedAt().toString()
                    );
                }
        );
        orderLifecycleService.evictStoreCancelledRedisViews(orderId, storeId, idempotencyKey);
        return response;
    }

    public StoreProposeOrderChangesResponse proposeChanges(
            String storeId,
            String orderId,
            ProposeOrderChangesRequest request,
            String idempotencyKey
    ) {
        StoreProposeOrderChangesResponse response = idempotentCommandService.execute(
                STORE_PROPOSE_CHANGES_ACTION,
                idempotencyKey,
                orderId,
                proposeChangesFingerprint(storeId, orderId, request),
                StoreProposeOrderChangesResponse.class,
                () -> {
                    UUID id = parseOrderId(orderId);
                    OrderEntity order = orderRepository.findByIdAndStoreId(id, storeId)
                            .orElseThrow(() -> new OrderNotFoundException(orderId));
                    if (order.getStatus() != OrderStatus.CONFIRMED) {
                        throw new OrderInvalidTransitionException(
                                orderId, order.getStatus(), OrderStatus.AWAITING_CUSTOMER_RESPONSE);
                    }
                    List<OrderItemEntity> orderItems = orderItemRepository.findByOrderIdOrderByLineNumberAsc(id);
                    String itemsJson = buildAndValidateProposalItems(orderId, request, orderItems);
                    OffsetDateTime proposedAt = OffsetDateTime.now();
                    OrderLifecycleResult result = orderLifecycleService.storeProposeChangesWithoutRedisUpdate(
                            orderId, storeId, itemsJson, proposedAt);
                    return new StoreProposeOrderChangesResponse(
                            orderId,
                            result.newStatus(),
                            result.changedAt().toString(),
                            toProposedItemResults(request.changes())
                    );
                }
        );
        orderLifecycleService.updateProposeChangesRedisViews(orderId, storeId, idempotencyKey);
        return response;
    }

    private String buildAndValidateProposalItems(
            String orderId,
            ProposeOrderChangesRequest request,
            List<OrderItemEntity> orderItems
    ) {
        Map<String, OrderItemEntity> byProductId = orderItems.stream()
                .collect(Collectors.toMap(OrderItemEntity::getProductId, Function.identity()));
        HashSet<String> seen = new HashSet<>();
        List<OrderProposalsCacheDto.ProposedItem> items = new ArrayList<>();

        for (ProposeOrderChangesRequest.ProposedItemChange change : request.changes()) {
            if (!seen.add(change.productId())) {
                throw new IllegalArgumentException("Duplicate productId in changes: " + change.productId());
            }
            OrderItemEntity orderItem = byProductId.get(change.productId());
            if (orderItem == null) {
                throw new IllegalArgumentException("productId does not belong to order: " + change.productId());
            }
            if (!orderItem.getProductName().equals(change.productName())) {
                throw new IllegalArgumentException(
                        "productName '" + change.productName() + "' does not match order item name '"
                                + orderItem.getProductName() + "' for productId: " + change.productId());
            }
            if (!orderItem.getUnit().equals(change.unit())) {
                throw new IllegalArgumentException(
                        "unit does not match order item for productId: " + change.productId());
            }
            if (orderItem.getQuantity().compareTo(change.requestedQuantity()) != 0) {
                throw new IllegalArgumentException(
                        "requestedQuantity " + change.requestedQuantity()
                                + " does not match order item quantity " + orderItem.getQuantity()
                                + " for product '" + orderItem.getProductName() + "' (" + change.productId() + ")");
            }
            validateProposalAvailableQuantity(change);
            items.add(OrderProposalsCacheDto.ProposedItem.builder()
                    .productId(change.productId())
                    .productName(change.productName())
                    .requestedQuantity(change.requestedQuantity())
                    .unit(change.unit())
                    .availableQuantity(change.availableQuantity())
                    .build());
        }
        return writeMetadata(items);
    }

    private void validateProposalAvailableQuantity(ProposeOrderChangesRequest.ProposedItemChange change) {
        BigDecimal available = change.availableQuantity();
        if (available == null) return;
        if (available.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "availableQuantity must be null for no stock, or greater than zero for partial stock: "
                            + change.productId());
        }
        if (available.compareTo(change.requestedQuantity()) >= 0) {
            throw new IllegalArgumentException(
                    "availableQuantity must be less than requestedQuantity for productId: " + change.productId());
        }
    }

    private List<StoreProposeOrderChangesResponse.ProposedItemChangeResult> toProposedItemResults(
            List<ProposeOrderChangesRequest.ProposedItemChange> changes
    ) {
        return changes.stream()
                .map(c -> new StoreProposeOrderChangesResponse.ProposedItemChangeResult(
                        c.productId(), c.productName(), c.requestedQuantity(), c.unit(), c.availableQuantity()))
                .toList();
    }

    private String proposeChangesFingerprint(String storeId, String orderId, ProposeOrderChangesRequest request) {
        return "storeId=" + storeId
                + ";orderId=" + orderId
                + ";changes=" + request.changes().stream()
                    .map(c -> c.productId() + ":" + c.productName() + ":"
                            + c.requestedQuantity() + ":" + c.unit() + ":" + c.availableQuantity())
                    .sorted()
                    .collect(Collectors.joining(","));
    }

    private OrderCancellationReason toCancellationReason(RejectOrderRequest.RejectionReason reason) {
        return switch (reason) {
            case STORE_REJECTED -> OrderCancellationReason.STORE_REJECTED;
            case OUT_OF_STOCK -> OrderCancellationReason.OUT_OF_STOCK;
        };
    }

    private OrderCancellationReason toCancellationReason(StoreCancelOrderRequest.CancellationReason reason) {
        return switch (reason) {
            case STORE_CLOSED -> OrderCancellationReason.STORE_CLOSED;
            case STORE_UNABLE_TO_FULFIL -> OrderCancellationReason.STORE_UNABLE_TO_FULFIL;
        };
    }

    private void validateCancelRequest(StoreCancelOrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Cancel request body is required");
        }
        if (request.reason() == null) {
            throw new IllegalArgumentException("reason is required");
        }
    }

    private String cancelRequestFingerprint(String storeId, String orderId, StoreCancelOrderRequest request) {
        return "storeId=" + storeId
                + ";orderId=" + orderId
                + ";reason=" + request.reason()
                + ";note=" + request.note();
    }

    private void validateRejectRequest(RejectOrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Reject request body is required");
        }
        if (request.reason() == RejectOrderRequest.RejectionReason.OUT_OF_STOCK) {
            if (request.outOfStockItems() == null || request.outOfStockItems().isEmpty()) {
                throw new IllegalArgumentException("outOfStockItems is required when reason is OUT_OF_STOCK");
            }
            if (request.outOfStockItems().stream().anyMatch(item -> item == null)) {
                throw new IllegalArgumentException("outOfStockItems must not contain null items");
            }
            return;
        }
        if (request.outOfStockItems() != null && !request.outOfStockItems().isEmpty()) {
            throw new IllegalArgumentException("outOfStockItems is only allowed when reason is OUT_OF_STOCK");
        }
    }

    private OrderEntity loadPendingStoreOrder(String orderId, String storeId) {
        UUID id = parseOrderId(orderId);
        OrderEntity order = orderRepository.findByIdAndStoreId(id, storeId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new OrderInvalidTransitionException(orderId, order.getStatus(), OrderStatus.CANCELLED);
        }
        return order;
    }

    private String rejectionMetadata(OrderEntity order, RejectOrderRequest request) {
        if (request.reason() != RejectOrderRequest.RejectionReason.OUT_OF_STOCK) {
            return writeMetadata(new RejectionMetadata(request.note(), List.of()));
        }

        List<OrderItemEntity> orderItems = orderItemRepository.findByOrderIdOrderByLineNumberAsc(order.getId());
        Map<String, OrderItemEntity> orderItemsByProductId = orderItems.stream()
                .collect(Collectors.toMap(OrderItemEntity::getProductId, Function.identity()));
        HashSet<String> seenProductIds = new HashSet<>();

        for (RejectOrderRequest.OutOfStockItem item : request.outOfStockItems()) {
            if (!seenProductIds.add(item.productId())) {
                throw new IllegalArgumentException("Duplicate outOfStockItems productId: " + item.productId());
            }

            OrderItemEntity orderItem = orderItemsByProductId.get(item.productId());
            if (orderItem == null) {
                throw new IllegalArgumentException("outOfStockItems productId does not belong to order: " + item.productId());
            }
            if (!orderItem.getUnit().equals(item.unit())) {
                throw new IllegalArgumentException("outOfStockItems unit does not match order item for productId: " + item.productId());
            }
            if (orderItem.getQuantity().compareTo(item.requestedQuantity()) != 0) {
                throw new IllegalArgumentException("outOfStockItems requestedQuantity does not match order item for productId: " + item.productId());
            }
            validateAvailableQuantity(item);
        }

        return writeMetadata(new RejectionMetadata(request.note(), request.outOfStockItems()));
    }

    private void validateAvailableQuantity(RejectOrderRequest.OutOfStockItem item) {
        BigDecimal availableQuantity = item.availableQuantity();
        if (availableQuantity == null) {
            return;
        }
        if (availableQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("availableQuantity must be null for no stock, or greater than zero for partial stock");
        }
        if (availableQuantity.compareTo(item.requestedQuantity()) >= 0) {
            throw new IllegalArgumentException("availableQuantity must be less than requestedQuantity for productId: " + item.productId());
        }
    }

    private UUID parseOrderId(String orderId) {
        try {
            return UUID.fromString(orderId);
        } catch (IllegalArgumentException exception) {
            throw new OrderNotFoundException(orderId);
        }
    }

    private String writeMetadata(Object metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize store order lifecycle metadata", exception);
        }
    }

    private record RejectionMetadata(
            String note,
            List<RejectOrderRequest.OutOfStockItem> outOfStockItems
    ) {}

    private record StoreCancelMetadata(String note) {}
}
