package com.sebet.order_service.order.command;

import com.sebet.order_service.persistence.entity.OrderEntity;

public record CreateOrderResult(
        OrderEntity order,
        boolean createdNewOrder
) {
}
