package com.sebet.order_service.order.service;

import com.sebet.order_service.order.command.CreateOrderCommand;
import com.sebet.order_service.order.command.CreateOrderItemCommand;
import com.sebet.order_service.order.command.CreateOrderResult;
import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.persistence.entity.OrderItemEntity;
import com.sebet.order_service.persistence.entity.OrderStatusHistoryEntity;
import com.sebet.order_service.persistence.repository.OrderItemRepository;
import com.sebet.order_service.persistence.repository.OrderRepository;
import com.sebet.order_service.persistence.repository.OrderStatusHistoryRepository;
import com.sebet.order_service.shared.enums.OrderStatus;
import com.sebet.order_service.shared.enums.ScheduleType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderCreationService {

    private static final String CHANGED_BY_SYSTEM = "SYSTEM";
    private static final String CHECKOUT_CONFIRMED_REASON = "CHECKOUT_CONFIRMED";

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Transactional
    public CreateOrderResult createOrder(CreateOrderCommand command) {
        return orderRepository.findByCartId(command.cartId())
                .map(existingOrder -> new CreateOrderResult(existingOrder, false))
                .orElseGet(() -> createNewOrder(command));
    }

    private CreateOrderResult createNewOrder(CreateOrderCommand command) {
        OffsetDateTime now = OffsetDateTime.now();
        OrderStatus initialStatus = initialStatus(command.scheduleType());

        OrderEntity order = orderRepository.save(OrderEntity.builder()
                .cartId(command.cartId())
                .customerId(command.customerId())
                .storeId(command.storeId())
                .status(initialStatus)
                .scheduleType(command.scheduleType())
                .scheduledFor(command.scheduledFor())
                .subtotalAmount(command.subtotalAmount())
                .itemDiscountAmount(command.itemDiscountAmount())
                .orderDiscountAmount(command.orderDiscountAmount())
                .deliveryFeeAmount(command.deliveryFeeAmount())
                .totalAmount(command.totalAmount())
                .currency(command.currency())
                .deliveryAddressJson(command.deliveryAddressJson())
                .deliveryLat(command.deliveryLat())
                .deliveryLng(command.deliveryLng())
                .storeLat(command.storeLat())
                .storeLng(command.storeLng())
                .createdAt(now)
                .updatedAt(now)
                .build());

        orderItemRepository.saveAll(toOrderItems(order, command.items(), now));
        orderStatusHistoryRepository.save(OrderStatusHistoryEntity.builder()
                .orderId(order.getId())
                .fromStatus(null)
                .toStatus(initialStatus)
                .changedByType(CHANGED_BY_SYSTEM)
                .reason(CHECKOUT_CONFIRMED_REASON)
                .createdAt(now)
                .build());

        return new CreateOrderResult(order, true);
    }

    private OrderStatus initialStatus(ScheduleType scheduleType) {
        if (scheduleType == ScheduleType.SCHEDULED) {
            return OrderStatus.SCHEDULED;
        }
        return OrderStatus.PENDING;
    }

    private List<OrderItemEntity> toOrderItems(
            OrderEntity order,
            List<CreateOrderItemCommand> items,
            OffsetDateTime createdAt
    ) {
        List<OrderItemEntity> entities = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            CreateOrderItemCommand item = items.get(index);
            entities.add(OrderItemEntity.builder()
                    .orderId(order.getId())
                    .lineNumber(index + 1)
                    .productId(item.productId())
                    .productName(item.productName())
                    .quantity(item.quantity())
                    .unit(item.unit())
                    .unitPriceAmount(item.unitPriceAmount())
                    .grossAmount(item.grossAmount())
                    .discountAmount(item.discountAmount())
                    .netAmount(item.netAmount())
                    .imageUrl(item.imageUrl())
                    .createdAt(createdAt)
                    .build());
        }
        return entities;
    }
}
