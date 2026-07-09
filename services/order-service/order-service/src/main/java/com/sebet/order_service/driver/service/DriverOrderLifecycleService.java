package com.sebet.order_service.driver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.cache.dto.DeliveryAddress;
import com.sebet.order_service.cache.dto.DriverInfo;
import com.sebet.order_service.cache.dto.OrderItem;
import com.sebet.order_service.cache.dto.RedisOrder;
import com.sebet.order_service.cache.dto.StoreLocation;
import com.sebet.order_service.cache.repository.OrderRedisRepository;
import com.sebet.order_service.cache.repository.OrderStatusRedisRepository;
import com.sebet.order_service.cache.dto.VerificationCodeCacheDto;
import com.sebet.order_service.cache.repository.VerificationCodeRedisRepository;
import com.sebet.order_service.cache.service.OrderCacheEvictionService;
import com.sebet.order_service.driver.dto.response.DriverArriveResponse;
import com.sebet.order_service.driver.dto.response.DriverCompleteDeliveryResponse;
import com.sebet.order_service.driver.dto.response.DriverDeclineResponse;
import com.sebet.order_service.driver.dto.response.DriverOrderDetailResponse;
import com.sebet.order_service.driver.dto.response.DriverPickupResponse;
import com.sebet.order_service.order.event.OrderEventOutboxWriter;
import com.sebet.order_service.order.service.OrderLifecycleResult;
import com.sebet.order_service.order.service.OrderLifecycleService;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OrderItemEntity;
import com.sebet.order_service.persistence.entity.OrderStatusHistoryEntity;
import com.sebet.order_service.persistence.repository.OrderItemRepository;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.persistence.repository.OrderStatusHistoryRepository;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.exception.DriverNotAssignedException;
import com.sebet.order_service.shared.exception.OrderInvalidTransitionException;
import com.sebet.order_service.shared.exception.OrderNotFoundException;
import com.sebet.order_service.shared.exception.VerificationCodeNotFoundException;
import com.sebet.order_service.shared.idempotency.IdempotentCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class DriverOrderLifecycleService {

    private static final EnumSet<OrderStatus> DRIVER_DECLINE_ALLOWED_STATUSES =
            EnumSet.of(OrderStatus.PENDING, OrderStatus.CONFIRMED, OrderStatus.READY_FOR_PICKUP);
    private static final String DRIVER_DECLINED_REASON = "DRIVER_DECLINED";
    private static final String IDEMPOTENT_DRIVER_DECLINE_ACTION = "DRIVER_DECLINE_ASSIGNMENT";

    private final OrderLifecycleService orderLifecycleService;
    private final OrderEventOutboxWriter orderEventOutboxWriter;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderRedisRepository orderRedisRepository;
    private final OrderStatusRedisRepository orderStatusRedisRepository;
    private final VerificationCodeRedisRepository verificationCodeRedisRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final ObjectMapper objectMapper;
    private final IdempotentCommandService idempotentCommandService;
    private final OrderCacheEvictionService orderCacheEvictionService;

    @Transactional(readOnly = true)
    public DriverOrderDetailResponse getOrderDetail(String driverId, String orderId) {
        DriverOrderDetailResponse cached = getOrderDetailFromCache(driverId, orderId);
        if (cached != null) {
            return cached;
        }

        OrderEntity order = loadAssignedOrder(driverId, orderId);
        List<OrderItemEntity> items = orderItemRepository.findByOrderIdOrderByLineNumberAsc(order.getId());

        return new DriverOrderDetailResponse(
                orderId,
                orderNumber(order.getId()),
                order.getStatus(),
                pickupPoint(order),
                dropoffPoint(order),
                itemLines(items),
                iso(order.getScheduledFor()),
                iso(order.getCreatedAt())
        );
    }

    public DriverPickupResponse confirmPickup(String driverId, String orderId) {
        OrderLifecycleResult result = orderLifecycleService.driverPickup(orderId, driverId);
        return new DriverPickupResponse(orderId, result.newStatus().name());
    }

    public DriverArriveResponse markArrived(String driverId, String orderId) {
        String code = generateVerificationCode();
        String metadataJson = serializeCodeMetadata(code);

        OrderLifecycleResult result = orderLifecycleService.driverArrive(orderId, driverId, metadataJson);

        verificationCodeRedisRepository.save(orderId, VerificationCodeCacheDto.builder()
                .code(code)
                .generatedAt(result.changedAt().toString())
                .build());

        return new DriverArriveResponse(orderId, result.newStatus().name());
    }

    public DriverCompleteDeliveryResponse completeDelivery(String driverId, String orderId, String verificationCode) {
        String storedCode = resolveVerificationCode(orderId);

        if (!storedCode.equals(verificationCode)) {
            throw new IllegalArgumentException("Verification code does not match");
        }

        OrderLifecycleResult result = orderLifecycleService.driverComplete(orderId, driverId);
        return new DriverCompleteDeliveryResponse(
                orderId,
                result.newStatus().name(),
                result.changedAt().toString()
        );
    }

    public DriverDeclineResponse declineAssignment(String driverId, String orderId, String idempotencyKey) {
        DriverDeclineResponse response = idempotentCommandService.execute(
                IDEMPOTENT_DRIVER_DECLINE_ACTION,
                idempotencyKey,
                orderId,
                "orderId=" + orderId + ";driverId=" + driverId,
                DriverDeclineResponse.class,
                () -> declineAssignmentInTransaction(driverId, orderId)
        );
        orderCacheEvictionService.evictC2OrRequestEviction(orderId, IDEMPOTENT_DRIVER_DECLINE_ACTION, idempotencyKey);
        return response;
    }

    private DriverDeclineResponse declineAssignmentInTransaction(String driverId, String orderId) {
        OrderEntity order = loadAssignedOrder(driverId, orderId);
        OrderStatus status = order.getStatus();
        if (!DRIVER_DECLINE_ALLOWED_STATUSES.contains(status)) {
            throw new OrderInvalidTransitionException(orderId, status, "DRIVER_DECLINE");
        }

        OffsetDateTime declinedAt = OffsetDateTime.now();
        String declinedDriverId = order.getDriverId();

        order.setDriverId(null);
        order.setDriverAssignedAt(null);
        order.setUpdatedAt(declinedAt);
        OrderEntity savedOrder = orderRepository.saveAndFlush(order);
        orderEventOutboxWriter.saveDriverAssignmentDeclined(
                savedOrder,
                declinedDriverId,
                declinedAt,
                DRIVER_DECLINED_REASON
        );

        return new DriverDeclineResponse(savedOrder.getId().toString(), savedOrder.getStatus().name());
    }

    private DriverOrderDetailResponse getOrderDetailFromCache(String driverId, String orderId) {
        RedisOrder cachedOrder = orderRedisRepository.findById(orderId).orElse(null);
        if (cachedOrder == null || cachedOrder.getDriver() == null) {
            return null;
        }

        if (!driverId.equals(cachedOrder.getDriver().getDriverId())) {
            return null;
        }

        OrderStatus status = orderStatusRedisRepository.findById(orderId)
                .map(OrderStatusRedisRepository.Entry::status)
                .flatMap(this::parseStatus)
                .orElse(null);
        if (status == null) {
            return null;
        }

        UUID id = parseOrderId(orderId);
        return new DriverOrderDetailResponse(
                orderId,
                orderNumber(id),
                status,
                pickupPoint(cachedOrder),
                dropoffPoint(cachedOrder),
                itemLines(cachedOrder),
                cachedOrder.getEstimatedDeliveryAt(),
                cachedOrder.getCreatedAt()
        );
    }

    private OrderEntity loadAssignedOrder(String driverId, String orderId) {
        OrderEntity order = orderRepository.findById(parseOrderId(orderId))
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (!driverId.equals(order.getDriverId())) {
            throw new DriverNotAssignedException(orderId);
        }
        return order;
    }

    private DriverOrderDetailResponse.PickupPoint pickupPoint(RedisOrder cachedOrder) {
        StoreLocation cachedLocation = cachedOrder.getStoreLocation();
        return new DriverOrderDetailResponse.PickupPoint(
                cachedOrder.getStoreId(),
                cachedOrder.getStoreName() != null ? cachedOrder.getStoreName() : cachedOrder.getStoreId(),
                cachedLocation != null ? cachedLocation.getLat() : 0.0,
                cachedLocation != null ? cachedLocation.getLng() : 0.0
        );
    }

    private DriverOrderDetailResponse.PickupPoint pickupPoint(OrderEntity order) {
        return new DriverOrderDetailResponse.PickupPoint(
                order.getStoreId(),
                order.getStoreId(),
                toDouble(order.getStoreLat()),
                toDouble(order.getStoreLng())
        );
    }

    private DriverOrderDetailResponse.DropoffPoint dropoffPoint(RedisOrder cachedOrder) {
        DeliveryAddress cachedAddress = cachedOrder.getDeliveryAddress();
        if (cachedAddress == null) {
            return null;
        }
        return new DriverOrderDetailResponse.DropoffPoint(
                cachedAddress.getStreet(),
                cachedAddress.getCity(),
                null,
                cachedAddress.getLat(),
                cachedAddress.getLng()
        );
    }

    private DriverOrderDetailResponse.DropoffPoint dropoffPoint(OrderEntity order) {
        JsonNode node = parseDeliveryAddress(order);
        return new DriverOrderDetailResponse.DropoffPoint(
                node.path("street").asText(null),
                node.path("city").asText(null),
                node.path("apartment").asText(null),
                toDouble(order.getDeliveryLat()),
                toDouble(order.getDeliveryLng())
        );
    }

    private List<DriverOrderDetailResponse.ItemLine> itemLines(RedisOrder cachedOrder) {
        List<OrderItem> cachedItems = cachedOrder.getItems();
        if (cachedItems == null) {
            return List.of();
        }
        return cachedItems.stream()
                .map(item -> new DriverOrderDetailResponse.ItemLine(
                        item.getProductId(),
                        item.getName(),
                        item.getQuantity(),
                        item.getUnitPrice()
                ))
                .toList();
    }

    private List<DriverOrderDetailResponse.ItemLine> itemLines(List<OrderItemEntity> entityItems) {
        return entityItems.stream()
                .map(item -> new DriverOrderDetailResponse.ItemLine(
                        item.getProductId(),
                        item.getProductName(),
                        item.getQuantity(),
                        item.getUnitPriceAmount()
                ))
                .toList();
    }

    private JsonNode parseDeliveryAddress(OrderEntity order) {
        try {
            return objectMapper.readTree(order.getDeliveryAddressJson());
        } catch (JsonProcessingException exception) {
            log.warn("Failed to parse deliveryAddressJson for order {}", order.getId(), exception);
            return objectMapper.createObjectNode();
        }
    }

    private String resolveVerificationCode(String orderId) {
        return verificationCodeRedisRepository.findById(orderId)
                .map(VerificationCodeCacheDto::getCode)
                .orElseGet(() -> resolveVerificationCodeFromDb(orderId));
    }

    private String resolveVerificationCodeFromDb(String orderId) {
        UUID id;
        try {
            id = UUID.fromString(orderId);
        } catch (IllegalArgumentException e) {
            throw new OrderNotFoundException(orderId);
        }

        return orderStatusHistoryRepository
                .findFirstByOrderIdAndToStatus(id, OrderStatus.ARRIVED)
                .map(this::extractCodeFromMetadata)
                .orElseThrow(() -> new VerificationCodeNotFoundException(orderId));
    }

    private String extractCodeFromMetadata(OrderStatusHistoryEntity history) {
        if (history.getMetadataJson() == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(history.getMetadataJson());
            JsonNode codeNode = node.get("code");
            return codeNode != null ? codeNode.asText() : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private UUID parseOrderId(String orderId) {
        try {
            return UUID.fromString(orderId);
        } catch (IllegalArgumentException exception) {
            throw new OrderNotFoundException(orderId);
        }
    }

    private String orderNumber(UUID id) {
        String hex = id.toString().replace("-", "");
        return "#" + hex.substring(hex.length() - 8).toUpperCase();
    }

    private Optional<OrderStatus> parseStatus(String value) {
        try {
            return Optional.of(OrderStatus.valueOf(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private double toDouble(BigDecimal value) {
        return value == null ? 0.0 : value.doubleValue();
    }

    private String iso(OffsetDateTime value) {
        return value == null ? null : value.toString();
    }

    private String generateVerificationCode() {
        return String.format("%02d", ThreadLocalRandom.current().nextInt(100));
    }

    private String serializeCodeMetadata(String code) {
        try {
            return objectMapper.writeValueAsString(new CodeMetadata(code));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize verification code metadata", e);
        }
    }

    private record CodeMetadata(String code) {}
}
