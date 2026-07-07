package com.sebet.order_service.order.service;

import com.sebet.order_service.persistence.entity.OrderEntity;
import com.sebet.order_service.shared.enums.OrderStatus;

import java.time.OffsetDateTime;

public record OrderLifecycleResult(
        OrderEntity order,
        OrderStatus previousStatus,
        OrderStatus newStatus,
        OffsetDateTime changedAt
) {}
