package com.sebet.order_service.store.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.cache.dto.DriverInfo;
import com.sebet.order_service.cache.dto.OrderItem;
import com.sebet.order_service.cache.dto.OrderProposalsCacheDto;
import com.sebet.order_service.cache.dto.RedisOrder;
import com.sebet.order_service.cache.repository.OrderProposalsRedisRepository;
import com.sebet.order_service.cache.repository.OrderRedisRepository;
import com.sebet.order_service.cache.repository.OrderStatusRedisRepository;
import com.sebet.order_service.cache.repository.StoreActiveOrdersRedisRepository;
import com.sebet.order_service.cache.repository.StoreScheduledOrdersRedisRepository;
import com.sebet.order_service.customer.dto.response.TimelineStepResponse;
import com.sebet.order_service.customer.dto.response.shared.DeliveryAddressDto;
import com.sebet.order_service.customer.dto.response.shared.OrderItemDto;
import com.sebet.order_service.customer.dto.response.shared.PricingDto;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OrderItemEntity;
import com.sebet.order_service.persistence.entity.OrderStatusHistoryEntity;
import com.sebet.order_service.persistence.repository.OrderItemRepository;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.persistence.repository.OrderStatusHistoryRepository;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.exception.OrderNotFoundException;
import com.sebet.order_service.store.dto.response.StoreActiveOrderItemResponse;
import com.sebet.order_service.store.dto.response.StoreOrderDetailResponse;
import com.sebet.order_service.store.dto.response.StoreOrderHistoryItemResponse;
import com.sebet.order_service.store.dto.response.StoreOrderStatusResponse;
import com.sebet.order_service.store.dto.response.StoreScheduledOrderItemResponse;
import com.sebet.order_service.store.dto.response.shared.CustomerInfoDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreOrderQueryService {

    private static final EnumSet<OrderStatus> HISTORY_STATUSES =
            EnumSet.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED);

    private static final EnumSet<OrderStatus> ACTIVE_STATUSES =
            EnumSet.of(
                    OrderStatus.PENDING,
                    OrderStatus.CONFIRMED,
                    OrderStatus.READY_FOR_PICKUP,
                    OrderStatus.OUT_FOR_DELIVERY,
                    OrderStatus.ARRIVED,
                    OrderStatus.AWAITING_CUSTOMER_RESPONSE
            );

    private static final EnumSet<OrderStatus> SCHEDULED_STATUSES =
            EnumSet.of(OrderStatus.SCHEDULED);

    private static final List<String> TIMELINE_STEPS =
            List.of("PLACED", "PACKED", "ON_THE_WAY", "ARRIVED");

    private static final Map<String, String> STEP_LABELS = Map.of(
            "PLACED", "Placed",
            "PACKED", "Packed",
            "ON_THE_WAY", "On the way",
            "ARRIVED", "Arrived"
    );

    private final StoreActiveOrdersRedisRepository storeActiveOrdersRedisRepository;
    private final StoreScheduledOrdersRedisRepository storeScheduledOrdersRedisRepository;
    private final OrderRedisRepository orderRedisRepository;
    private final OrderStatusRedisRepository orderStatusRedisRepository;
    private final OrderProposalsRedisRepository orderProposalsRedisRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final ObjectMapper objectMapper;

    public Page<StoreOrderHistoryItemResponse> getOrderHistory(String storeId, Pageable pageable) {
        Page<OrderEntity> page = orderRepository.findByStoreIdAndStatusIn(storeId, HISTORY_STATUSES, pageable);
        if (page.isEmpty()) {
            return page.map(order -> toHistoryItem(order, List.of()));
        }
        List<UUID> orderIds = page.getContent().stream().map(OrderEntity::getId).toList();
        Map<UUID, List<OrderItemEntity>> itemsByOrder = orderItemRepository
                .findByOrderIdInOrderByOrderIdAscLineNumberAsc(orderIds)
                .stream()
                .collect(Collectors.groupingBy(OrderItemEntity::getOrderId));
        return page.map(order -> toHistoryItem(order, itemsByOrder.getOrDefault(order.getId(), List.of())));
    }

    public List<StoreActiveOrderItemResponse> getActiveOrders(String storeId) {
        Set<String> activeOrderIds = storeActiveOrdersRedisRepository.getAll(storeId);
        List<StoreActiveOrderItemResponse> cached = activeOrderIds.stream()
                .map(orderId -> toCachedActiveOrderItem(storeId, orderId))
                .flatMap(Optional::stream)
                .toList();
        if (!activeOrderIds.isEmpty() && cached.size() == activeOrderIds.size()) {
            return cached;
        }

        return orderRepository.findByStoreIdAndStatusInOrderByCreatedAtDesc(storeId, ACTIVE_STATUSES).stream()
                .map(order -> toActiveOrderItem(order, orderItemRepository.findByOrderIdOrderByLineNumberAsc(order.getId())))
                .toList();
    }

    public List<StoreScheduledOrderItemResponse> getScheduledOrders(String storeId) {
        Set<String> scheduledOrderIds = storeScheduledOrdersRedisRepository.getAll(storeId);
        List<StoreScheduledOrderItemResponse> cached = scheduledOrderIds.stream()
                .map(orderRedisRepository::findById)
                .flatMap(Optional::stream)
                .filter(order -> storeId.equals(order.getStoreId()))
                .map(this::toScheduledOrderItem)
                .toList();
        if (!scheduledOrderIds.isEmpty() && cached.size() == scheduledOrderIds.size()) {
            return cached;
        }

        return orderRepository.findByStoreIdAndStatusInOrderByScheduledForAsc(storeId, SCHEDULED_STATUSES).stream()
                .map(order -> toScheduledOrderItem(order, orderItemRepository.findByOrderIdOrderByLineNumberAsc(order.getId())))
                .toList();
    }

    public StoreOrderDetailResponse getOrderDetail(String storeId, String orderId) {
        OrderEntity order = orderRepository.findByIdAndStoreId(parseOrderId(orderId), storeId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        List<OrderItemEntity> items = orderItemRepository.findByOrderIdOrderByLineNumberAsc(order.getId());
        List<TimelineStepResponse> timeline = buildTimelineFromHistory(order.getId());
        return toOrderDetail(order, items, timeline);
    }

    public StoreOrderStatusResponse getOrderStatus(String storeId, String orderId) {
        Optional<OrderStatusRedisRepository.Entry> cachedStatus = orderStatusRedisRepository.findById(orderId);
        if (cachedStatus.isPresent()) {
            if (!storeId.equals(cachedStatus.get().storeId())) {
                throw new OrderNotFoundException(orderId);
            }
            Optional<OrderStatus> status = parseStatus(cachedStatus.get().status());
            if (status.isPresent()) {
                return new StoreOrderStatusResponse(orderId, status.get());
            }
        }

        OrderEntity order = orderRepository.findByIdAndStoreId(parseOrderId(orderId), storeId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        return new StoreOrderStatusResponse(orderId, order.getStatus());
    }

    private StoreOrderHistoryItemResponse toHistoryItem(OrderEntity order, List<OrderItemEntity> items) {
        List<String> thumbnails = items.stream()
                .limit(3)
                .map(OrderItemEntity::getImageUrl)
                .filter(Objects::nonNull)
                .toList();
        StoreOrderHistoryItemResponse.OrderDetailRoute route = order.getStatus() == OrderStatus.DELIVERED
                ? StoreOrderHistoryItemResponse.OrderDetailRoute.DELIVERED
                : StoreOrderHistoryItemResponse.OrderDetailRoute.CANCELLED;
        StoreOrderHistoryItemResponse.CancellationInfo cancellation = order.getStatus() == OrderStatus.CANCELLED
                ? new StoreOrderHistoryItemResponse.CancellationInfo(order.getCancelledBy(), order.getCancellationReason())
                : null;

        return new StoreOrderHistoryItemResponse(
                order.getId().toString(),
                orderNumber(order.getId()),
                route,
                customerInfo(order.getCustomerId()),
                thumbnails,
                items.size(),
                order.getTotalAmount(),
                order.getCurrency(),
                iso(order.getCreatedAt()),
                order.getStatus() == OrderStatus.DELIVERED ? iso(order.getDeliveredAt()) : null,
                order.getStatus() == OrderStatus.CANCELLED ? iso(order.getCancelledAt()) : null,
                cancellation
        );
    }

    private Optional<StoreActiveOrderItemResponse> toCachedActiveOrderItem(String storeId, String orderId) {
        Optional<RedisOrder> snapshot = orderRedisRepository.findById(orderId);
        if (snapshot.isEmpty() || !storeId.equals(snapshot.get().getStoreId())) {
            return Optional.empty();
        }
        Optional<OrderStatus> status = orderStatusRedisRepository.findById(orderId)
                .flatMap(entry -> parseStatus(entry.status()));
        return status.map(orderStatus -> toActiveOrderItem(snapshot.get(), orderStatus));
    }

    private StoreActiveOrderItemResponse toActiveOrderItem(RedisOrder order, OrderStatus status) {
        List<OrderItemDto> items = toOrderItemDtosFromCache(order.getItems());

        return new StoreActiveOrderItemResponse(
                order.getOrderId(),
                orderNumber(UUID.fromString(order.getOrderId())),
                status,
                items,
                items.size(),
                order.getCreatedAt(),
                toActiveDriverDto(order.getDriver())
        );
    }

    private StoreActiveOrderItemResponse toActiveOrderItem(OrderEntity order, List<OrderItemEntity> items) {
        List<OrderItemDto> itemDtos = toOrderItemDtosFromEntity(items);
        return new StoreActiveOrderItemResponse(
                order.getId().toString(),
                orderNumber(order.getId()),
                order.getStatus(),
                itemDtos,
                itemDtos.size(),
                iso(order.getCreatedAt()),
                activeDriverDtoFromEntity(order)
        );
    }

    private StoreScheduledOrderItemResponse toScheduledOrderItem(RedisOrder order) {
        List<OrderItemDto> items = toOrderItemDtosFromCache(order.getItems());
        return new StoreScheduledOrderItemResponse(
                order.getOrderId(),
                orderNumber(UUID.fromString(order.getOrderId())),
                OrderStatus.SCHEDULED,
                order.getEstimatedDeliveryAt(),
                customerInfo(order.getUserId()),
                items,
                items.size(),
                order.getCreatedAt()
        );
    }

    private StoreScheduledOrderItemResponse toScheduledOrderItem(OrderEntity order, List<OrderItemEntity> items) {
        List<OrderItemDto> itemDtos = toOrderItemDtosFromEntity(items);
        return new StoreScheduledOrderItemResponse(
                order.getId().toString(),
                orderNumber(order.getId()),
                order.getStatus(),
                iso(order.getScheduledFor()),
                customerInfo(order.getCustomerId()),
                itemDtos,
                itemDtos.size(),
                iso(order.getCreatedAt())
        );
    }

    private StoreOrderDetailResponse toOrderDetail(
            OrderEntity order,
            List<OrderItemEntity> items,
            List<TimelineStepResponse> timeline
    ) {
        return new StoreOrderDetailResponse(
                order.getId().toString(),
                orderNumber(order.getId()),
                order.getStatus(),
                customerInfo(order.getCustomerId()),
                toDeliveryAddressDtoFromEntity(order),
                toOrderItemDtosFromEntity(items),
                toPricingDto(order),
                order.getCurrency(),
                iso(order.getCreatedAt()),
                iso(order.getScheduledFor()),
                timeline,
                driverDtoFromEntity(order),
                pendingProposal(order),
                cancellationDetail(order)
        );
    }

    private StoreOrderDetailResponse.PendingProposal pendingProposal(OrderEntity order) {
        if (order.getStatus() != OrderStatus.AWAITING_CUSTOMER_RESPONSE) {
            return null;
        }
        return orderProposalsRedisRepository.find(order.getId().toString())
                .map(proposal -> new StoreOrderDetailResponse.PendingProposal(
                        proposal.getProposedAt(),
                        proposal.getItems() == null ? List.of() : proposal.getItems().stream()
                                .map(this::toProposedItemDetail)
                                .toList()
                ))
                .orElse(null);
    }

    private StoreOrderDetailResponse.PendingProposal.ProposedItemDetail toProposedItemDetail(
            OrderProposalsCacheDto.ProposedItem item
    ) {
        return new StoreOrderDetailResponse.PendingProposal.ProposedItemDetail(
                item.getProductId(),
                item.getProductName(),
                item.getRequestedQuantity(),
                item.getUnit(),
                item.getAvailableQuantity()
        );
    }

    private StoreOrderDetailResponse.CancellationDetail cancellationDetail(OrderEntity order) {
        if (order.getStatus() != OrderStatus.CANCELLED) {
            return null;
        }
        return new StoreOrderDetailResponse.CancellationDetail(
                order.getCancelledBy(),
                order.getCancellationReason(),
                iso(order.getCancelledAt())
        );
    }

    private List<TimelineStepResponse> buildTimelineFromHistory(UUID orderId) {
        Map<String, String> timestamps = new LinkedHashMap<>();
        String previousStep = null;
        for (OrderStatusHistoryEntity entry : orderStatusHistoryRepository.findByOrderIdOrderByCreatedAtAsc(orderId)) {
            String step = toTimelineStep(entry.getToStatus());
            if (step != null && !step.equals(previousStep)) {
                timestamps.putIfAbsent(step, iso(entry.getCreatedAt()));
                previousStep = step;
            }
        }
        return TIMELINE_STEPS.stream()
                .map(step -> new TimelineStepResponse(step, STEP_LABELS.get(step), timestamps.get(step)))
                .toList();
    }

    private String toTimelineStep(OrderStatus status) {
        return switch (status) {
            case PENDING -> "PLACED";
            case READY_FOR_PICKUP -> "PACKED";
            case OUT_FOR_DELIVERY -> "ON_THE_WAY";
            case DELIVERED -> "ARRIVED";
            default -> null;
        };
    }

    private Optional<OrderStatus> parseStatus(String value) {
        try {
            return Optional.of(OrderStatus.valueOf(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private PricingDto toPricingDto(OrderEntity order) {
        BigDecimal promoDiscount = order.getItemDiscountAmount().add(order.getOrderDiscountAmount());
        return new PricingDto(
                order.getSubtotalAmount(),
                order.getDeliveryFeeAmount(),
                BigDecimal.ZERO,
                promoDiscount,
                order.getTotalAmount()
        );
    }

    private DeliveryAddressDto toDeliveryAddressDtoFromEntity(OrderEntity order) {
        try {
            JsonNode node = objectMapper.readTree(order.getDeliveryAddressJson());
            return new DeliveryAddressDto(
                    node.path("street").asText(null),
                    node.path("city").asText(null),
                    order.getDeliveryLat().doubleValue(),
                    order.getDeliveryLng().doubleValue()
            );
        } catch (JsonProcessingException exception) {
            log.warn("Failed to parse deliveryAddressJson for order {}", order.getId(), exception);
            return new DeliveryAddressDto(
                    null,
                    null,
                    order.getDeliveryLat().doubleValue(),
                    order.getDeliveryLng().doubleValue()
            );
        }
    }

    private List<OrderItemDto> toOrderItemDtosFromCache(List<OrderItem> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .map(item -> new OrderItemDto(
                        item.getProductId(),
                        item.getName(),
                        item.getImageUrl(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getSubtotal()
                ))
                .toList();
    }

    private List<OrderItemDto> toOrderItemDtosFromEntity(List<OrderItemEntity> items) {
        return items.stream()
                .map(item -> new OrderItemDto(
                        item.getProductId(),
                        item.getProductName(),
                        item.getImageUrl(),
                        item.getQuantity(),
                        item.getUnitPriceAmount(),
                        item.getGrossAmount()
                ))
                .toList();
    }

    private StoreActiveOrderItemResponse.DriverDto toActiveDriverDto(DriverInfo driver) {
        if (driver == null) {
            return null;
        }
        return new StoreActiveOrderItemResponse.DriverDto(
                driver.getDriverId(),
                driver.getName(),
                driver.getPhone(),
                driver.getRating(),
                driver.getVehicle(),
                driver.getPlateNumber()
        );
    }

    private StoreOrderDetailResponse.DriverDto driverDtoFromEntity(OrderEntity order) {
        if (order.getDriverId() == null) {
            return null;
        }
        return new StoreOrderDetailResponse.DriverDto(
                order.getDriverId(),
                null,
                null,
                0.0,
                null,
                null
        );
    }

    private StoreActiveOrderItemResponse.DriverDto activeDriverDtoFromEntity(OrderEntity order) {
        if (order.getDriverId() == null) {
            return null;
        }
        return new StoreActiveOrderItemResponse.DriverDto(
                order.getDriverId(),
                null,
                null,
                0.0,
                null,
                null
        );
    }

    private CustomerInfoDto customerInfo(String customerId) {
        return new CustomerInfoDto(maskCustomerId(customerId));
    }

    private String maskCustomerId(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            return "Customer";
        }
        int visibleLength = Math.min(6, customerId.length());
        return "Customer " + customerId.substring(0, visibleLength);
    }

    private String orderNumber(UUID id) {
        String hex = id.toString().replace("-", "");
        return "#" + hex.substring(hex.length() - 8).toUpperCase();
    }

    private UUID parseOrderId(String orderId) {
        try {
            return UUID.fromString(orderId);
        } catch (IllegalArgumentException exception) {
            throw new OrderNotFoundException(orderId);
        }
    }

    private String iso(OffsetDateTime value) {
        return value == null ? null : value.toString();
    }
}
