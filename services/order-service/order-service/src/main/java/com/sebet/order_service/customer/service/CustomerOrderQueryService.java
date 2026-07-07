package com.sebet.order_service.customer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.cache.dto.DeliveryAddress;
import com.sebet.order_service.cache.dto.DriverInfo;
import com.sebet.order_service.cache.dto.OrderItem;
import com.sebet.order_service.cache.dto.OrderProposalsCacheDto;
import com.sebet.order_service.cache.dto.OrderTimelineEntry;
import com.sebet.order_service.cache.dto.RedisOrder;
import com.sebet.order_service.cache.dto.RedisOrderTracking;
import com.sebet.order_service.cache.dto.StoreLocation;
import com.sebet.order_service.cache.dto.VerificationCodeCacheDto;
import com.sebet.order_service.cache.repository.ActiveOrdersRedisRepository;
import com.sebet.order_service.cache.repository.OrderProposalsRedisRepository;
import com.sebet.order_service.cache.repository.OrderRedisRepository;
import com.sebet.order_service.cache.repository.OrderStatusRedisRepository;
import com.sebet.order_service.cache.repository.OrderTimelineRedisRepository;
import com.sebet.order_service.cache.repository.OrderTrackingRedisRepository;
import com.sebet.order_service.cache.repository.VerificationCodeRedisRepository;
import com.sebet.order_service.customer.dto.response.ActiveOrderDetailResponse;
import com.sebet.order_service.customer.dto.response.ActiveOrderItemResponse;
import com.sebet.order_service.customer.dto.response.CancelledOrderDetailResponse;
import com.sebet.order_service.customer.dto.response.DeliveredOrderDetailResponse;
import com.sebet.order_service.customer.dto.response.OrderHistoryItemResponse;
import com.sebet.order_service.customer.dto.response.OrderProposedChangesResponse;
import com.sebet.order_service.customer.dto.response.OrderStatusResponse;
import com.sebet.order_service.customer.dto.response.OrderTrackingResponse;
import com.sebet.order_service.customer.dto.response.ScheduledOrderDetailResponse;
import com.sebet.order_service.customer.dto.response.TimelineStepResponse;
import com.sebet.order_service.customer.dto.response.VerificationCodeResponse;
import com.sebet.order_service.customer.dto.response.shared.DeliveryAddressDto;
import com.sebet.order_service.customer.dto.response.shared.OrderItemDto;
import com.sebet.order_service.customer.dto.response.shared.PricingDto;
import com.sebet.order_service.customer.dto.response.shared.StoreLocationDto;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OrderItemEntity;
import com.sebet.order_service.persistence.entity.OrderStatusHistoryEntity;
import com.sebet.order_service.persistence.repository.OrderItemRepository;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.persistence.repository.OrderStatusHistoryRepository;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.RefundStatus;
import com.sebet.order_service.shared.exception.OrderNotFoundException;
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
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerOrderQueryService {

    private static final EnumSet<OrderStatus> HISTORY_STATUSES =
            EnumSet.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED, OrderStatus.SCHEDULED);

    private static final List<String> TIMELINE_STEPS =
            List.of("PLACED", "PACKED", "ON_THE_WAY", "ARRIVED");

    private static final Map<String, String> STEP_LABELS = Map.of(
            "PLACED", "Placed",
            "PACKED", "Packed",
            "ON_THE_WAY", "On the way",
            "ARRIVED", "Arrived"
    );

    private final OrderRedisRepository orderRedisRepository;
    private final OrderStatusRedisRepository orderStatusRedisRepository;
    private final OrderTimelineRedisRepository orderTimelineRedisRepository;
    private final OrderTrackingRedisRepository orderTrackingRedisRepository;
    private final ActiveOrdersRedisRepository activeOrdersRedisRepository;
    private final VerificationCodeRedisRepository verificationCodeRedisRepository;
    private final OrderProposalsRedisRepository orderProposalsRedisRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final ObjectMapper objectMapper;

    // ── History ──────────────────────────────────────────────────────────────

    public Page<OrderHistoryItemResponse> getOrderHistory(String userId, Pageable pageable) {
        Page<OrderEntity> page = orderRepository.findByCustomerIdAndStatusIn(userId, HISTORY_STATUSES, pageable);
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

    // ── Active ───────────────────────────────────────────────────────────────

    public List<ActiveOrderItemResponse> getActiveOrders(String userId) {
        return activeOrdersRedisRepository.getAll(userId).stream()
                .map(orderId -> orderRedisRepository.findById(orderId).orElse(null))
                .filter(Objects::nonNull)
                .map(this::toActiveOrderItem)
                .toList();
    }

    public ActiveOrderDetailResponse getActiveOrderDetail(String userId, String orderId) {
        RedisOrder snapshot = orderRedisRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (!userId.equals(snapshot.getUserId())) {
            throw new OrderNotFoundException(orderId);
        }

        OrderStatus status = orderStatusRedisRepository.findById(orderId)
                .map(e -> OrderStatus.valueOf(e.status()))
                .orElse(null);

        List<TimelineStepResponse> timeline = buildTimeline(
                orderTimelineRedisRepository.findAll(orderId));

        String verificationCode = null;
        if (status == OrderStatus.ARRIVED) {
            verificationCode = verificationCodeRedisRepository.findById(orderId)
                    .map(VerificationCodeCacheDto::getCode)
                    .orElse(null);
        }

        return new ActiveOrderDetailResponse(
                orderId,
                orderNumber(UUID.fromString(orderId)),
                status,
                storeName(snapshot),
                snapshot.getStoreId(),
                toStoreLocationDto(snapshot.getStoreLocation()),
                toDeliveryAddressDto(snapshot.getDeliveryAddress()),
                toOrderItemDtosFromCache(snapshot.getItems(), true),
                toDriverDto(snapshot.getDriver()),
                snapshot.getTotalAmount(),
                "TRY",  // TODO: add currency to RedisOrder
                snapshot.getCreatedAt(),
                verificationCode,
                timeline
        );
    }

    // ── Scheduled ────────────────────────────────────────────────────────────

    public ScheduledOrderDetailResponse getScheduledOrderDetail(String userId, String orderId) {
        OrderEntity order = orderRepository.findByIdAndCustomerId(parseOrderId(orderId), userId)
                .filter(o -> o.getStatus() == OrderStatus.SCHEDULED)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        List<OrderItemEntity> items =
                orderItemRepository.findByOrderIdOrderByLineNumberAsc(order.getId());

        return new ScheduledOrderDetailResponse(
                orderId,
                orderNumber(order.getId()),
                iso(order.getScheduledFor()),
                order.getStoreId(),  // TODO: store name from store service
                order.getStoreId(),
                toDeliveryAddressDtoFromEntity(order),
                toOrderItemDtosFromEntity(items, false),
                toPricingDto(order),
                order.getCurrency(),
                canCancel(order),
                iso(order.getCreatedAt())
        );
    }

    // ── Cancelled ────────────────────────────────────────────────────────────

    public CancelledOrderDetailResponse getCancelledOrderDetail(String userId, String orderId) {
        OrderEntity order = orderRepository.findByIdAndCustomerId(parseOrderId(orderId), userId)
                .filter(o -> o.getStatus() == OrderStatus.CANCELLED)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        List<OrderItemEntity> items =
                orderItemRepository.findByOrderIdOrderByLineNumberAsc(order.getId());

        return new CancelledOrderDetailResponse(
                orderId,
                orderNumber(order.getId()),
                order.getStoreId(),  // TODO: store name from store service
                order.getStoreId(),
                toDeliveryAddressDtoFromEntity(order),
                toOrderItemDtosFromEntity(items, false),
                toPricingDto(order),
                order.getCurrency(),
                iso(order.getCancelledAt()),
                order.getCancelledBy(),
                order.getCancellationReason(),
                new CancelledOrderDetailResponse.RefundInfo(RefundStatus.REFUND_PENDING, null, null),
                iso(order.getCreatedAt())
        );
    }

    // ── Smart router ─────────────────────────────────────────────────────────

    public CustomerOrderRouterResult routeOrderDetail(String userId, String orderId) {
        OrderEntity order = orderRepository.findByIdAndCustomerId(parseOrderId(orderId), userId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        return switch (order.getStatus()) {
            case DELIVERED -> new CustomerOrderRouterResult.Delivered(buildDeliveredDetail(order, orderId));
            case CANCELLED -> new CustomerOrderRouterResult.Redirect("/api/v1/orders/cancelled/" + orderId);
            case SCHEDULED -> new CustomerOrderRouterResult.Redirect("/api/v1/orders/scheduled/" + orderId);
            default        -> new CustomerOrderRouterResult.Redirect("/api/v1/orders/active/" + orderId);
        };
    }

    // ── Status & Tracking ────────────────────────────────────────────────────

    public OrderStatusResponse getOrderStatus(String userId, String orderId) {
        Optional<OrderStatusRedisRepository.Entry> cached = orderStatusRedisRepository.findById(orderId);
        if (cached.isPresent()) {
            if (!userId.equals(cached.get().userId())) {
                throw new OrderNotFoundException(orderId);
            }
            return new OrderStatusResponse(orderId, OrderStatus.valueOf(cached.get().status()));
        }
        OrderEntity entity = orderRepository.findByIdAndCustomerId(parseOrderId(orderId), userId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        return new OrderStatusResponse(orderId, entity.getStatus());
    }

    public OrderTrackingResponse getOrderTracking(String userId, String orderId) {
        verifyOwnership(orderId, userId);

        String status = orderStatusRedisRepository.findById(orderId).map(e -> e.status()).orElse(null);
        Optional<RedisOrderTracking> tracking = orderTrackingRedisRepository.findById(orderId);

        return new OrderTrackingResponse(
                orderId,
                status,
                tracking.map(RedisOrderTracking::getEtaMinutes).orElse(null),
                tracking.map(RedisOrderTracking::getDriverLat).orElse(null),
                tracking.map(RedisOrderTracking::getDriverLng).orElse(null),
                tracking.map(RedisOrderTracking::getUpdatedAt).orElse(null)
        );
    }

    // ── Verification code ────────────────────────────────────────────────────

    public VerificationCodeResponse getVerificationCode(String userId, String orderId) {
        verifyOwnership(orderId, userId);
        VerificationCodeCacheDto dto = verificationCodeRedisRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        return new VerificationCodeResponse(orderId, dto.getCode());
    }

    // ── Proposed changes ─────────────────────────────────────────────────────

    public OrderProposedChangesResponse getProposedChanges(String userId, String orderId) {
        verifyOwnership(orderId, userId);
        OrderProposalsCacheDto proposals = orderProposalsRedisRepository.find(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        List<OrderProposedChangesResponse.ProposedItem> items =
                proposals.getItems() == null ? List.of() :
                proposals.getItems().stream()
                        .map(p -> new OrderProposedChangesResponse.ProposedItem(
                                p.getProductId(),
                                p.getProductName(),
                                p.getRequestedQuantity(),
                                p.getUnit(),
                                p.getAvailableQuantity()))
                        .toList();

        return new OrderProposedChangesResponse(orderId, proposals.getProposedAt(), items);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void verifyOwnership(String orderId, String userId) {
        Optional<RedisOrder> snapshot = orderRedisRepository.findById(orderId);
        if (snapshot.isPresent()) {
            if (!userId.equals(snapshot.get().getUserId())) {
                throw new OrderNotFoundException(orderId);
            }
            return;
        }
        orderRepository.findByIdAndCustomerId(parseOrderId(orderId), userId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private DeliveredOrderDetailResponse buildDeliveredDetail(OrderEntity order, String orderId) {
        List<OrderItemEntity> items =
                orderItemRepository.findByOrderIdOrderByLineNumberAsc(order.getId());
        List<TimelineStepResponse> timeline = buildTimelineFromHistory(order.getId());

        return new DeliveredOrderDetailResponse(
                orderId,
                orderNumber(order.getId()),
                order.getStoreId(),  // TODO: store name from store service
                order.getStoreId(),
                toDeliveryAddressDtoFromEntity(order),
                toOrderItemDtosFromEntity(items, false),
                toPricingDto(order),
                order.getCurrency(),
                iso(order.getCreatedAt()),
                iso(order.getDeliveredAt()),
                timeline
        );
    }

    private List<TimelineStepResponse> buildTimeline(List<OrderTimelineEntry> entries) {
        Map<String, String> timestamps = entries.stream()
                .collect(Collectors.toMap(
                        OrderTimelineEntry::getStatus,
                        OrderTimelineEntry::getOccurredAt,
                        (a, b) -> a));
        return TIMELINE_STEPS.stream()
                .map(step -> new TimelineStepResponse(step, STEP_LABELS.get(step), timestamps.get(step)))
                .toList();
    }

    private List<TimelineStepResponse> buildTimelineFromHistory(UUID orderId) {
        Map<String, String> timestamps = new LinkedHashMap<>();
        String prevStep = null;
        for (OrderStatusHistoryEntity entry :
                orderStatusHistoryRepository.findByOrderIdOrderByCreatedAtAsc(orderId)) {
            String step = toTimelineStep(entry.getToStatus());
            if (step != null && !step.equals(prevStep)) {
                timestamps.putIfAbsent(step, iso(entry.getCreatedAt()));
                prevStep = step;
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

    private OrderHistoryItemResponse toHistoryItem(OrderEntity order, List<OrderItemEntity> items) {
        List<String> thumbnails = items.stream()
                .limit(3)
                .map(OrderItemEntity::getImageUrl)
                .filter(Objects::nonNull)
                .toList();

        OrderHistoryItemResponse.OrderDetailRoute route = switch (order.getStatus()) {
            case DELIVERED -> OrderHistoryItemResponse.OrderDetailRoute.DELIVERED;
            case CANCELLED -> OrderHistoryItemResponse.OrderDetailRoute.CANCELLED;
            default        -> OrderHistoryItemResponse.OrderDetailRoute.SCHEDULED;
        };

        OrderHistoryItemResponse.RefundInfo refund = order.getStatus() == OrderStatus.CANCELLED
                ? new OrderHistoryItemResponse.RefundInfo(RefundStatus.REFUND_PENDING, null)
                : null;

        return new OrderHistoryItemResponse(
                order.getId().toString(),
                orderNumber(order.getId()),
                route,
                order.getStoreId(),  // TODO: store name from store service
                order.getTotalAmount(),
                order.getCurrency(),
                items.size(),
                thumbnails,
                iso(order.getCreatedAt()),
                order.getStatus() == OrderStatus.SCHEDULED ? iso(order.getScheduledFor()) : null,
                refund
        );
    }

    private ActiveOrderItemResponse toActiveOrderItem(RedisOrder order) {
        List<String> thumbnails = order.getItems() == null ? List.of() :
                order.getItems().stream()
                        .limit(3)
                        .map(OrderItem::getImageUrl)
                        .filter(Objects::nonNull)
                        .toList();
        int itemCount = order.getItems() == null ? 0 : order.getItems().size();

        return new ActiveOrderItemResponse(
                order.getOrderId(),
                orderNumber(UUID.fromString(order.getOrderId())),
                storeName(order),
                order.getStoreId(),
                thumbnails,
                itemCount,
                order.getTotalAmount(),
                "TRY",  // TODO: add currency to RedisOrder
                toDeliveryAddressDto(order.getDeliveryAddress()),
                order.getCreatedAt()
        );
    }

    private PricingDto toPricingDto(OrderEntity order) {
        BigDecimal promoDiscount = order.getItemDiscountAmount().add(order.getOrderDiscountAmount());
        return new PricingDto(
                order.getSubtotalAmount(),
                order.getDeliveryFeeAmount(),
                BigDecimal.ZERO,  // TODO: track service fee separately
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
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse deliveryAddressJson for order {}", order.getId(), e);
            return new DeliveryAddressDto(null, null,
                    order.getDeliveryLat().doubleValue(),
                    order.getDeliveryLng().doubleValue());
        }
    }

    private DeliveryAddressDto toDeliveryAddressDto(DeliveryAddress address) {
        if (address == null) return null;
        return new DeliveryAddressDto(address.getStreet(), address.getCity(),
                address.getLat(), address.getLng());
    }

    private StoreLocationDto toStoreLocationDto(StoreLocation location) {
        if (location == null) return null;
        return new StoreLocationDto(location.getLat(), location.getLng());
    }

    private List<OrderItemDto> toOrderItemDtosFromCache(List<OrderItem> items, boolean includeProductId) {
        if (items == null) return List.of();
        return items.stream()
                .map(item -> new OrderItemDto(
                        includeProductId ? item.getProductId() : null,
                        item.getName(),
                        item.getImageUrl(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getSubtotal()))
                .toList();
    }

    private List<OrderItemDto> toOrderItemDtosFromEntity(List<OrderItemEntity> items, boolean includeProductId) {
        return items.stream()
                .map(item -> new OrderItemDto(
                        includeProductId ? item.getProductId() : null,
                        item.getProductName(),
                        item.getImageUrl(),
                        item.getQuantity().intValueExact(),
                        item.getUnitPriceAmount(),
                        item.getGrossAmount()))
                .toList();
    }

    private ActiveOrderDetailResponse.DriverDto toDriverDto(DriverInfo driver) {
        if (driver == null) return null;
        return new ActiveOrderDetailResponse.DriverDto(
                driver.getDriverId(),
                driver.getName(),
                driver.getPhone(),
                driver.getRating(),
                driver.getVehicle(),
                driver.getPlateNumber()
        );
    }

    private boolean canCancel(OrderEntity order) {
        if (order.getScheduledFor() == null) return false;
        return OffsetDateTime.now().plusHours(1).isBefore(order.getScheduledFor());
    }

    private String storeName(RedisOrder snapshot) {
        return snapshot.getStoreName() != null ? snapshot.getStoreName() : snapshot.getStoreId();
    }

    private String orderNumber(UUID id) {
        String hex = id.toString().replace("-", "");
        return "#" + hex.substring(hex.length() - 8).toUpperCase();
    }

    private UUID parseOrderId(String orderId) {
        try {
            return UUID.fromString(orderId);
        } catch (IllegalArgumentException e) {
            throw new OrderNotFoundException(orderId);
        }
    }

    private String iso(OffsetDateTime value) {
        return value == null ? null : value.toString();
    }
}
