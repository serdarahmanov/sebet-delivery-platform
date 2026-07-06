package com.sebet.order_service.cache.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sebet.order_service.cache.dto.DeliveryAddress;
import com.sebet.order_service.cache.dto.OrderItem;
import com.sebet.order_service.cache.dto.OrderTimelineEntry;
import com.sebet.order_service.cache.dto.RedisOrder;
import com.sebet.order_service.cache.dto.StoreLocation;
import com.sebet.order_service.cache.repository.ActiveOrdersRedisRepository;
import com.sebet.order_service.cache.repository.OrderRedisRepository;
import com.sebet.order_service.cache.repository.OrderStatusRedisRepository;
import com.sebet.order_service.cache.repository.OrderTimelineRedisRepository;
import com.sebet.order_service.cache.repository.StoreActiveOrdersRedisRepository;
import com.sebet.order_service.cache.repository.StoreScheduledOrdersRedisRepository;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OrderItemEntity;
import com.sebet.order_service.persistence.entity.OrderStatusHistoryEntity;
import com.sebet.order_service.persistence.repository.OrderItemRepository;
import com.sebet.order_service.persistence.repository.OrderStatusHistoryRepository;
import com.sebet.order_service.shared.enums.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderCreationRedisWriter {

    private final OrderRedisRepository orderRedisRepository;
    private final OrderStatusRedisRepository orderStatusRedisRepository;
    private final OrderTimelineRedisRepository orderTimelineRedisRepository;
    private final ActiveOrdersRedisRepository activeOrdersRedisRepository;
    private final StoreActiveOrdersRedisRepository storeActiveOrdersRedisRepository;
    private final StoreScheduledOrdersRedisRepository storeScheduledOrdersRedisRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final ObjectMapper objectMapper;

    public void ensureCreatedOrder(OrderEntity order) {
        String orderId = order.getId().toString();

        if (orderRedisRepository.findById(orderId).isEmpty()) {
            orderRedisRepository.save(toRedisOrder(order));
        }

        orderStatusRedisRepository.save(orderId, order.getStatus().name());

        if (orderTimelineRedisRepository.findAll(orderId).isEmpty()) {
            for (OrderTimelineEntry entry : toTimelineEntries(order)) {
                orderTimelineRedisRepository.append(orderId, entry);
            }
        }

        if (order.getStatus() == OrderStatus.SCHEDULED) {
            if (!storeScheduledOrdersRedisRepository.contains(order.getStoreId(), orderId)) {
                storeScheduledOrdersRedisRepository.add(
                        order.getStoreId(),
                        orderId,
                        order.getScheduledFor().toInstant()
                );
            }
            return;
        }

        if (isActiveStatus(order.getStatus())) {
            if (!activeOrdersRedisRepository.contains(order.getCustomerId(), orderId)) {
                activeOrdersRedisRepository.add(order.getCustomerId(), orderId);
            }
            if (!storeActiveOrdersRedisRepository.contains(order.getStoreId(), orderId)) {
                storeActiveOrdersRedisRepository.add(order.getStoreId(), orderId);
            }
        }
    }

    private RedisOrder toRedisOrder(OrderEntity order) {
        return RedisOrder.builder()
                .orderId(order.getId().toString())
                .userId(order.getCustomerId())
                .storeId(order.getStoreId())
                .cartId(order.getCartId())
                .totalAmount(order.getTotalAmount())
                .deliveryAddress(toDeliveryAddress(order))
                .storeLocation(toStoreLocation(order))
                .items(toRedisItems(order.getId()))
                .estimatedDeliveryAt(order.getScheduleType() == com.sebet.order_service.shared.enums.ScheduleType.SCHEDULED
                        ? iso(order.getScheduledFor())
                        : null)
                .createdAt(iso(order.getCreatedAt()))
                .updatedAt(iso(order.getUpdatedAt()))
                .build();
    }

    private DeliveryAddress toDeliveryAddress(OrderEntity order) {
        try {
            JsonNode address = objectMapper.readTree(order.getDeliveryAddressJson());
            return DeliveryAddress.builder()
                    .street(address.path("street").asText(null))
                    .city(address.path("city").asText(null))
                    .lat(order.getDeliveryLat().doubleValue())
                    .lng(order.getDeliveryLng().doubleValue())
                    .build();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Failed to deserialize checkout delivery address for Redis snapshot",
                    exception
            );
        }
    }

    private StoreLocation toStoreLocation(OrderEntity order) {
        if (order.getStoreLat() == null || order.getStoreLng() == null) {
            return null;
        }
        return StoreLocation.builder()
                .lat(order.getStoreLat().doubleValue())
                .lng(order.getStoreLng().doubleValue())
                .build();
    }

    private List<OrderItem> toRedisItems(UUID orderId) {
        List<OrderItemEntity> items = orderItemRepository.findByOrderIdOrderByLineNumberAsc(orderId);
        return items.stream()
                .map(item -> OrderItem.builder()
                        .productId(item.getProductId())
                        .name(item.getProductName())
                        .quantity(item.getQuantity().intValueExact())
                        .unitPrice(item.getUnitPriceAmount())
                        .subtotal(item.getGrossAmount())
                        .build())
                .toList();
    }

    private List<OrderTimelineEntry> toTimelineEntries(OrderEntity order) {
        List<OrderTimelineEntry> entries = new ArrayList<>();
        String previousTimelineStatus = null;
        for (OrderStatusHistoryEntity historyEntry
                : orderStatusHistoryRepository.findByOrderIdOrderByCreatedAtAsc(order.getId())) {
            String timelineStatus = toTimelineStatus(historyEntry.getToStatus());
            if (timelineStatus == null || timelineStatus.equals(previousTimelineStatus)) {
                continue;
            }
            entries.add(OrderTimelineEntry.builder()
                    .status(timelineStatus)
                    .occurredAt(iso(historyEntry.getCreatedAt()))
                    .build());
            previousTimelineStatus = timelineStatus;
        }
        if (entries.isEmpty() && order.getStatus() == OrderStatus.SCHEDULED) {
            entries.add(OrderTimelineEntry.builder()
                    .status("PLACED")
                    .occurredAt(iso(order.getCreatedAt()))
                    .build());
        }
        return entries;
    }

    private boolean isActiveStatus(OrderStatus status) {
        return switch (status) {
            case PENDING,
                 CONFIRMED,
                 READY_FOR_PICKUP,
                 DRIVER_ASSIGNED,
                 OUT_FOR_DELIVERY,
                 ARRIVED,
                 AWAITING_CUSTOMER_RESPONSE -> true;
            default -> false;
        };
    }

    private String toTimelineStatus(OrderStatus status) {
        return switch (status) {
            case PENDING -> "PLACED";
            case CONFIRMED, READY_FOR_PICKUP -> "PACKED";
            case OUT_FOR_DELIVERY -> "ON_THE_WAY";
            case DELIVERED -> "ARRIVED";
            default -> null;
        };
    }

    private String iso(OffsetDateTime value) {
        return value == null ? null : value.toString();
    }
}
